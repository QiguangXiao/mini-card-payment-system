package com.minicard.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires one overdue authorization and releases its reserved credit atomically.
 *
 * <p>Processing one item per transaction keeps database locks short and ensures
 * a corrupt item cannot roll back unrelated expiry work in the same scheduler
 * run.</p>
 */
@Service
public class AuthorizationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationExpiryService.class);

    private final AuthorizationRepository authorizationRepository;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final AuthorizationExpiryEventPublisher eventPublisher;
    private final Clock clock;

    public AuthorizationExpiryService(
            AuthorizationRepository authorizationRepository,
            CardRepository cardRepository,
            CreditAccountRepository creditAccountRepository,
            AuthorizationExpiryEventPublisher eventPublisher,
            Clock clock
    ) {
        this.authorizationRepository = authorizationRepository;
        this.cardRepository = cardRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public void expire(UUID authorizationId) {
        Instant now = Instant.now(clock);
        Authorization authorization = authorizationRepository
                .findByIdForUpdate(authorizationId)
                .orElseThrow(() -> new IllegalStateException(
                        "authorization expiry job references missing authorization "
                                + authorizationId
                ));

        if (!authorization.status().isApproved()) {
            // The job can be safely completed when the business state no longer
            // needs expiry. This makes retry and manual replay idempotent.
            log.info(
                    "authorization_expiry_skipped authorizationId={} status={}",
                    authorization.id(),
                    authorization.status()
            );
            return;
        }
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new IllegalStateException(
                        "approved authorization has no expiresAt"
                ));
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("authorization expiry job ran before expiresAt");
        }

        // The delay_jobs row is already locked by DelayJobService. We still
        // lock the authorization row before reading status/expiresAt because
        // the business row is the source of truth for whether release is valid.
        Card card = cardRepository.findById(authorization.cardId())
                .orElseThrow(() -> new IllegalStateException(
                        "approved authorization references missing card "
                                + authorization.cardId()
                ));
        // The account row lock serializes expiry releases with new credit
        // reservations. This prevents a concurrent request from calculating
        // available credit from a stale reserved amount.
        CreditAccount account = creditAccountRepository.findByIdForUpdate(card.creditAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "approved authorization references missing credit account "
                                + card.creditAccountId()
                ));

        account.release(authorization.requestedAmount());
        authorization.expire(now);

        // Account release, authorization transition, and Outbox insert share
        // this transaction. A failure in any step rolls back all three changes.
        creditAccountRepository.update(account);
        authorizationRepository.update(authorization);
        eventPublisher.append(authorization);
        log.info(
                "authorization_expired authorizationId={} accountId={} amount={} currency={}",
                authorization.id(),
                account.id(),
                authorization.requestedAmount().amount(),
                authorization.requestedAmount().currency().getCurrencyCode()
        );
    }
}
