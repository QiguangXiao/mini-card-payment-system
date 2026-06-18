package com.minicard.transaction.application;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;

import com.minicard.authorization.application.AuthorizationDomainEventPublisher;
import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.authorization.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.transaction.domain.CardTransaction;
import com.minicard.transaction.domain.CardTransactionRepository;
import com.minicard.transaction.domain.CardTransactionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuer 侧 presentment posting use case。
 *
 * <p>Presentment 是外部网络/商户正式请款；posting 是发卡行把交易入账到持卡人账户。
 * 这里刻意不叫 capture/settlement：capture 偏商户侧，settlement 偏资金清算，不等于持卡人账户入账。</p>
 */
@Service
public class PostingService {

    private final CardTransactionRepository transactionRepository;
    private final AuthorizationRepository authorizationRepository;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final AuthorizationDomainEventPublisher eventPublisher;
    private final Clock clock;

    public PostingService(
            CardTransactionRepository transactionRepository,
            AuthorizationRepository authorizationRepository,
            CardRepository cardRepository,
            CreditAccountRepository creditAccountRepository,
            AuthorizationDomainEventPublisher eventPublisher,
            Clock clock
    ) {
        this.transactionRepository = transactionRepository;
        this.authorizationRepository = authorizationRepository;
        this.cardRepository = cardRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public CardTransaction post(PostPresentmentCommand command) {
        Instant now = Instant.now(clock);
        Authorization authorization = authorizationRepository
                .findByIdForUpdate(command.authorizationId())
                .orElseThrow(() -> new NoSuchElementException(
                        "authorization not found: " + command.authorizationId()
                ));

        Card card = cardRepository.findById(authorization.cardId())
                .orElseThrow(() -> new PresentmentRejectedException(
                        "posted authorization references missing card " + authorization.cardId()
                ));
        CardTransaction pending = CardTransaction.pending(
                command.networkTransactionId(),
                authorization.id(),
                authorization.cardId(),
                card.creditAccountId(),
                command.money(),
                now
        );

        // networkTransactionId 是 presentment 的天然 idempotency key。
        // 先 INSERT claim，再改账户余额，避免 retry/并发 duplicate 造成 double posting。
        boolean claimed = transactionRepository.claim(pending);
        CardTransaction transaction = transactionRepository
                .findByNetworkTransactionIdForUpdate(command.networkTransactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "presentment claim was not visible"
                ));
        assertSamePresentment(command, transaction);
        if (!claimed) {
            // duplicate retry 读取已完成的 posted transaction，不能再次释放 hold 或增加 postedBalance。
            if (transaction.status() == CardTransactionStatus.POSTED) {
                return transaction;
            }
            throw new PresentmentRejectedException(
                    "presentment is already being processed: " + command.networkTransactionId()
            );
        }

        validateAuthorizationCanBePosted(authorization, command.money(), now);
        CreditAccount account = creditAccountRepository.findByIdForUpdate(card.creditAccountId())
                .orElseThrow(() -> new PresentmentRejectedException(
                        "approved authorization references missing credit account "
                                + card.creditAccountId()
                ));

        // 同一个 transaction 内完成三件事：
        // 1. reservedAmount -> postedBalance，2. Authorization APPROVED -> POSTED，
        // 3. CardTransaction PENDING -> POSTED。任一步失败都会 rollback。
        account.postAuthorized(command.money());
        authorization.post(now);
        transaction.markPosted(now);

        creditAccountRepository.update(account);
        authorizationRepository.update(authorization);
        transactionRepository.update(transaction);
        publishDomainEvents(authorization);
        return transaction;
    }

    private void validateAuthorizationCanBePosted(
            Authorization authorization,
            Money presentmentAmount,
            Instant postingTime
    ) {
        if (authorization.status() != AuthorizationStatus.APPROVED) {
            throw new PresentmentRejectedException(
                    "authorization must be APPROVED before posting: " + authorization.status()
            );
        }
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new PresentmentRejectedException(
                        "approved authorization is missing expiresAt"
                ));
        if (postingTime.isAfter(expiresAt)) {
            throw new PresentmentRejectedException(
                    "authorization expired before presentment posting"
            );
        }
        if (!authorization.requestedAmount().equals(presentmentAmount)) {
            // 当前阶段只支持 full presentment。partial presentment 会引入 remaining hold，
            // 适合作为后续扩展，不在本阶段提前复杂化。
            throw new PresentmentRejectedException(
                    "presentment amount must equal authorization amount"
            );
        }
    }

    private void assertSamePresentment(
            PostPresentmentCommand command,
            CardTransaction transaction
    ) {
        if (!command.matches(transaction)) {
            throw new PresentmentConflictException();
        }
    }

    private void publishDomainEvents(Authorization authorization) {
        for (AuthorizationDomainEvent event : authorization.pullDomainEvents()) {
            eventPublisher.append(event);
        }
    }
}
