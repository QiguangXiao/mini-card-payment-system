package com.minicard.authorization.application;

import java.time.Clock;
import java.time.Instant;

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
    public boolean expireNext() {
        Instant now = Instant.now(clock);
        Authorization authorization = authorizationRepository
                .findNextExpiredApprovedForUpdate(now)
                .orElse(null);
        if (authorization == null) {
            return false;
        }

        // The authorization row is locked first. Multiple scheduler instances
        // use SKIP LOCKED, so only one transaction can release this hold.
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
        return true;
    }
}
