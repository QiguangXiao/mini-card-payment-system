package com.minicard.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDecision;
import com.minicard.authorization.domain.AuthorizationDecisionPolicy;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardAuthorizationFailure;
import com.minicard.card.domain.CardAuthorizationResult;
import com.minicard.card.domain.CardRepository;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditReservationFailure;
import com.minicard.creditaccount.domain.CreditReservationResult;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizationRepository;
    private final AuthorizationDecisionPolicy decisionPolicy;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final RiskAssessmentService riskAssessmentService;
    private final AuthorizationDecisionEventPublisher eventPublisher;
    private final AuthorizationExpiryJobScheduler expiryJobScheduler;
    private final Clock clock;

    public AuthorizationService(
            AuthorizationRepository authorizationRepository,
            AuthorizationDecisionPolicy decisionPolicy,
            CardRepository cardRepository,
            CreditAccountRepository creditAccountRepository,
            RiskAssessmentService riskAssessmentService,
            AuthorizationDecisionEventPublisher eventPublisher,
            AuthorizationExpiryJobScheduler expiryJobScheduler,
            Clock clock
    ) {
        this.authorizationRepository = authorizationRepository;
        this.decisionPolicy = decisionPolicy;
        this.cardRepository = cardRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.riskAssessmentService = riskAssessmentService;
        this.eventPublisher = eventPublisher;
        this.expiryJobScheduler = expiryJobScheduler;
        this.clock = clock;
    }

    @Transactional
    public Authorization authorize(AuthorizationCommand command) {
        Instant now = Instant.now(clock);
        // Start with a PENDING aggregate so every request has an auditable row
        // before any business decision is made. This mirrors a real issuer flow:
        // receive request -> record attempt -> decide -> persist final outcome.
        Authorization pending = Authorization.request(
                command.requestFingerprint(),
                command.cardId(),
                command.requestedAmount(),
                now
        );


        // The idempotency claim is deliberately the first write in the
        // transaction. If two identical client retries arrive at the same time,
        // only the winner is allowed to continue to card/account checks and
        // reserve credit. The loser reads the winner's result below.
        boolean claimed = authorizationRepository.claim(command.idempotencyKey(), pending);
        Authorization persisted = authorizationRepository
                .findByIdempotencyKeyForUpdate(command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "authorization claim was not visible"
                ));
        // A reused idempotency key is only valid when it represents the exact
        // same business request. Otherwise, the caller could accidentally hide a
        // different charge behind an old retry key.
        returnIdempotentResult(persisted, command);

        if (!claimed) {
            // Existing result means this is a retry or concurrent duplicate.
            // Returning here is what prevents double-reserving available credit.
            return persisted;
        }

        // Only the idempotency winner reaches the real decision path.
        decideAndReserve(persisted, command, now);
        authorizationRepository.update(persisted);
        if (persisted.status().isApproved()) {
            // The delay job is written in the same transaction as the approval.
            // This keeps "reserved credit must eventually expire" consistent
            // with the authorization row that made the hold visible.
            expiryJobScheduler.schedule(persisted);
        }
        // The Outbox row is inserted in this same MySQL transaction. We never
        // publish directly to Kafka here because a broker/network failure must
        // not leave an approved authorization without a recoverable event.
        eventPublisher.append(persisted);
        return persisted;
    }

    @Transactional(readOnly = true)
    public Authorization get(UUID id) {
        return authorizationRepository.findById(id)
                .orElseThrow(() -> new AuthorizationNotFoundException(id));
    }

    private void decideAndReserve(
            Authorization authorization,
            AuthorizationCommand command,
            Instant now
    ) {
        // Cheap policy checks run before locking account rows. This keeps the
        // critical section short under high traffic.
        AuthorizationDecision decision = decisionPolicy.decide(authorization);
        if (!decision.approved()) {
            // The aggregate applies the decision so status invariants remain in
            // one place instead of being assigned by the service.
            authorization.apply(decision, now);
            return;
        }

        // Card is a separate aggregate: it answers "may this plastic/token be
        // used?" while CreditAccount answers "is there enough available limit?"
        Card card = cardRepository.findById(authorization.cardId()).orElse(null);
        if (card == null) {
            authorization.decline(AuthorizationDeclineReason.CARD_NOT_FOUND, now);
            return;
        }
        CardAuthorizationResult eligibility = card.checkAuthorizationEligibility();
        if (!eligibility.eligible()) {
            // Keep card-specific reasons in the Card domain, then translate them
            // into Authorization decline reasons for the API and audit record.
            authorization.decline(mapCardFailure(eligibility.failure()), now);
            return;
        }

        // Risk check runs before the credit-account row lock. That order keeps
        // external latency and algorithmic scoring out of the hot critical
        // section where concurrent authorizations queue for the same account.
        RiskDecision riskDecision = riskAssessmentService.assess(command.toRiskAssessmentRequest());
        if (!riskDecision.approved()) {
            authorization.decline(mapRiskFailure(riskDecision.declineReason()), now);
            return;
        }

        // This SELECT ... FOR UPDATE is the core concurrency control for credit
        // limit. Every authorization for the same account queues here before
        // checking available credit and changing reservedAmount.
        CreditAccount account = creditAccountRepository
                .findByIdForUpdate(card.creditAccountId())
                .orElse(null);
        if (account == null) {
            authorization.decline(AuthorizationDeclineReason.CREDIT_ACCOUNT_NOT_FOUND, now);
            return;
        }

        // reserve() is a domain behavior, not arithmetic in the service. The
        // CreditAccount aggregate owns the invariant that reserved <= limit.
        CreditReservationResult reservation = account.reserve(authorization.requestedAmount());
        if (!reservation.reserved()) {
            authorization.decline(mapFailure(reservation.failure()), now);
            return;
        }

        // Persist account first, then approve the authorization in the same
        // transaction. If either write fails, the transaction rolls back and the
        // system will not show an approval without the matching credit reserve.
        creditAccountRepository.update(account);
        authorization.approve(now);
    }

    private AuthorizationDeclineReason mapCardFailure(CardAuthorizationFailure failure) {
        return switch (failure) {
            case CARD_BLOCKED -> AuthorizationDeclineReason.CARD_BLOCKED;
            case CARD_EXPIRED -> AuthorizationDeclineReason.CARD_EXPIRED;
        };
    }

    private AuthorizationDeclineReason mapFailure(CreditReservationFailure failure) {
        return switch (failure) {
            case ACCOUNT_BLOCKED -> AuthorizationDeclineReason.CREDIT_ACCOUNT_BLOCKED;
            case CURRENCY_MISMATCH -> AuthorizationDeclineReason.CURRENCY_MISMATCH;
            case INSUFFICIENT_AVAILABLE_CREDIT ->
                    AuthorizationDeclineReason.INSUFFICIENT_AVAILABLE_CREDIT;
        };
    }

    private AuthorizationDeclineReason mapRiskFailure(RiskDeclineReason failure) {
        return switch (failure) {
            case VELOCITY_EXCEEDED -> AuthorizationDeclineReason.RISK_VELOCITY_EXCEEDED;
            case HIGH_RISK_AMOUNT -> AuthorizationDeclineReason.RISK_HIGH_AMOUNT;
            case GEOLOCATION_MISMATCH -> AuthorizationDeclineReason.RISK_GEOLOCATION_MISMATCH;
            case BLOCKED_MERCHANT -> AuthorizationDeclineReason.RISK_BLOCKED_MERCHANT;
            case EXTERNAL_RISK_DECLINED -> AuthorizationDeclineReason.RISK_EXTERNAL_DECLINED;
            case EXTERNAL_RISK_UNAVAILABLE -> AuthorizationDeclineReason.RISK_EXTERNAL_UNAVAILABLE;
        };
    }

    private void returnIdempotentResult(
            Authorization existing,
            AuthorizationCommand command
    ) {
        if (!command.matches(existing)) {
            throw new IdempotencyConflictException();
        }
    }
}
