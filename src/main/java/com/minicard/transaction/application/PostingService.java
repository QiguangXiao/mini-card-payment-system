package com.minicard.transaction.application;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

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
import com.minicard.transaction.domain.event.CardTransactionDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuer 侧 presentment posting use case。
 *
 * <p>Presentment 是外部网络/商户正式请款；posting 是发卡行把交易入账到持卡人账户。
 * 这里刻意不叫 capture/settlement：capture 偏商户侧，settlement 偏资金清算，不等于持卡人账户入账。</p>
 */
@Service
@RequiredArgsConstructor
public class PostingService {

    private final CardTransactionRepository transactionRepository;
    private final AuthorizationRepository authorizationRepository;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final AuthorizationDomainEventPublisher authorizationEventPublisher;
    private final CardTransactionDomainEventPublisher transactionEventPublisher;
    private final Clock clock;

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

        Optional<CardTransaction> existingTransaction = transactionRepository
                .findByNetworkTransactionIdForUpdate(command.networkTransactionId());
        if (existingTransaction.isPresent()) {
            CardTransaction transaction = existingTransaction.get();
            assertSamePresentment(command, transaction);
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

        CardTransaction pending = CardTransaction.pending(
                command.networkTransactionId(),
                authorization.id(),
                authorization.cardId(),
                card.creditAccountId(),
                command.money(),
                now
        );

        // networkTransactionId 是 presentment 的天然 idempotency key。
        // 新 presentment 必须在 credit account row lock 之后再 INSERT claim：
        // StatementService 也先锁 account，再锁待出账交易，统一锁顺序可以避免 posting/statement 死锁，
        // 并保证账单生成期间不会漏掉正在入账的交易。
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

        // 同一个 transaction 内完成三件事：
        // 1. reservedAmount -> postedBalance，2. Authorization APPROVED -> POSTED，
        // 3. CardTransaction PENDING -> POSTED。任一步失败都会 rollback。
        account.postAuthorized(command.money());
        authorization.post(now);
        transaction.markPosted(now);

        creditAccountRepository.update(account);
        authorizationRepository.update(authorization);
        transactionRepository.update(transaction);
        // 两个 aggregate 都发生了状态转换，但语义不同：
        // Authorization event 表达授权生命周期结束；CardTransaction event 表达用户交易已入账。
        // 两类 Outbox rows 仍和本次 posting transaction 一起提交，避免消息与状态不一致。
        publishAuthorizationEvents(authorization);
        publishCardTransactionEvents(transaction);
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

    private void publishAuthorizationEvents(Authorization authorization) {
        for (AuthorizationDomainEvent event : authorization.pullDomainEvents()) {
            authorizationEventPublisher.append(event);
        }
    }

    private void publishCardTransactionEvents(CardTransaction transaction) {
        for (CardTransactionDomainEvent event : transaction.pullDomainEvents()) {
            transactionEventPublisher.append(event);
        }
    }
}
