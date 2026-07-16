package com.minicard.transaction.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.minicard.authorization.application.AuthorizationDomainEventAppender;
import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationPostedDomainEvent;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.domain.CardStatus;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import com.minicard.transaction.domain.CardTransaction;
import com.minicard.transaction.domain.CardTransactionRepository;
import com.minicard.transaction.domain.CardTransactionStatus;
import com.minicard.transaction.domain.event.CardTransactionDomainEvent;
import com.minicard.transaction.domain.event.CardTransactionPostedDomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostingServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");

    private CardTransactionRepository transactionRepository;
    private AuthorizationRepository authorizationRepository;
    private CardRepository cardRepository;
    private CreditAccountRepository creditAccountRepository;
    private AuthorizationDomainEventAppender authorizationEventAppender;
    private CardTransactionDomainEventAppender transactionEventAppender;
    private PostingService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(CardTransactionRepository.class);
        authorizationRepository = mock(AuthorizationRepository.class);
        cardRepository = mock(CardRepository.class);
        creditAccountRepository = mock(CreditAccountRepository.class);
        authorizationEventAppender = mock(AuthorizationDomainEventAppender.class);
        transactionEventAppender = mock(CardTransactionDomainEventAppender.class);
        service = new PostingService(
                transactionRepository,
                authorizationRepository,
                cardRepository,
                creditAccountRepository,
                authorizationEventAppender,
                transactionEventAppender,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    // 测试目的：验证 presentment happy path 会把 APPROVED authorization 入账为 POSTED transaction。
    // variant：金额完全匹配、account 有 reserved hold，锁顺序应为 account -> transaction claim。
    void postsApprovedAuthorizationIntoCardTransaction() {
        UUID accountId = UUID.randomUUID();
        Authorization authorization = approvedAuthorization("card-123", "100.00");
        CreditAccount account = account(accountId, "1000.00", "100.00", "0.00");
        arrangeNewPresentment();
        when(authorizationRepository.findByIdForUpdate(authorization.id()))
                .thenReturn(Optional.of(authorization));
        when(cardRepository.findById("card-123"))
                .thenReturn(Optional.of(new Card("card-123", accountId, CardStatus.ACTIVE)));
        when(creditAccountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        CardTransaction transaction = service.post(command(authorization.id(), "100.00"));

        assertThat(transaction.status()).isEqualTo(CardTransactionStatus.POSTED);
        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.POSTED);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("0.00");
        assertThat(account.postedBalance().amount()).isEqualByComparingTo("100.00");
        InOrder lockOrder = inOrder(creditAccountRepository, transactionRepository);
        lockOrder.verify(creditAccountRepository).findByIdForUpdate(accountId);
        lockOrder.verify(transactionRepository).claim(any());
        verify(creditAccountRepository).update(account);
        verify(authorizationRepository).update(authorization);
        verify(transactionRepository).update(transaction);
        ArgumentCaptor<AuthorizationDomainEvent> event =
                ArgumentCaptor.forClass(AuthorizationDomainEvent.class);
        verify(authorizationEventAppender).append(event.capture());
        assertThat(event.getValue()).isInstanceOf(AuthorizationPostedDomainEvent.class);
        ArgumentCaptor<CardTransactionDomainEvent> transactionEvent =
                ArgumentCaptor.forClass(CardTransactionDomainEvent.class);
        verify(transactionEventAppender).append(transactionEvent.capture());
        assertThat(transactionEvent.getValue()).isInstanceOf(CardTransactionPostedDomainEvent.class);
    }

    @Test
    // 测试目的：验证 network_transaction_id 的幂等重试直接返回已 POSTED transaction。
    // variant：claim=false 且已存在交易，不再锁 account、不再更新 authorization 或发布事件。
    void returnsExistingPostedTransactionForIdempotentRetry() {
        UUID accountId = UUID.randomUUID();
        Authorization authorization = approvedAuthorization("card-123", "100.00");
        CardTransaction existing = postedTransaction(authorization.id(), accountId);
        when(authorizationRepository.findByIdForUpdate(authorization.id()))
                .thenReturn(Optional.of(authorization));
        when(cardRepository.findById("card-123"))
                .thenReturn(Optional.of(new Card("card-123", accountId, CardStatus.ACTIVE)));
        when(transactionRepository.claim(any())).thenReturn(false);
        when(transactionRepository.findByNetworkTransactionIdForUpdate("ntx-001"))
                .thenReturn(Optional.of(existing));

        CardTransaction result = service.post(command(authorization.id(), "100.00"));

        assertThat(result).isSameAs(existing);
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
        verify(authorizationRepository, never()).update(any());
        verify(transactionRepository, never()).update(any());
        verify(authorizationEventAppender, never()).append(any());
        verify(transactionEventAppender, never()).append(any());
    }

    @Test
    // 测试目的：验证非 APPROVED authorization 不能被 presentment 入账。
    // variant：authorization 已 EXPIRED，业务拒绝而不是释放/扣减 account。
    void rejectsPresentmentWhenAuthorizationIsNotApproved() {
        UUID authorizationId = UUID.randomUUID();
        Authorization authorization = Authorization.restore(
                authorizationId,
                "fingerprint",
                "card-123",
                money("100.00"),
                AuthorizationStatus.EXPIRED,
                null,
                NOW.minusSeconds(10),
                NOW.minusSeconds(9),
                NOW.minusSeconds(1),
                null,
                NOW
        );
        arrangeNewPresentment();
        when(authorizationRepository.findByIdForUpdate(authorizationId))
                .thenReturn(Optional.of(authorization));
        when(cardRepository.findById("card-123"))
                .thenReturn(Optional.of(new Card("card-123", UUID.randomUUID(), CardStatus.ACTIVE)));

        assertThatThrownBy(() -> service.post(command(authorizationId, "100.00")))
                .isInstanceOf(PresentmentRejectedException.class)
                .hasMessageContaining("authorization must be APPROVED");
    }

    private void arrangeNewPresentment() {
        AtomicReference<CardTransaction> claimed = new AtomicReference<>();
        when(transactionRepository.claim(any())).thenAnswer(invocation -> {
            claimed.set(invocation.getArgument(0));
            return true;
        });
        when(transactionRepository.findByNetworkTransactionIdForUpdate("ntx-001"))
                .thenAnswer(invocation -> claimed.get() == null
                        ? Optional.empty()
                        : Optional.of(claimed.get()));
    }

    private PostPresentmentCommand command(UUID authorizationId, String amount) {
        return new PostPresentmentCommand(
                "ntx-001",
                authorizationId,
                new BigDecimal(amount),
                Currency.getInstance("JPY")
        );
    }

    private Authorization approvedAuthorization(String cardId, String amount) {
        Authorization authorization = Authorization.request(
                "fingerprint",
                cardId,
                money(amount),
                NOW.minusSeconds(10)
        );
        authorization.approve(NOW.minusSeconds(9));
        authorization.pullDomainEvents();
        return authorization;
    }

    private CreditAccount account(
            UUID id,
            String limit,
            String reserved,
            String posted
    ) {
        return CreditAccount.restore(
                id,
                money(limit),
                money(reserved),
                money(posted),
                CreditAccountStatus.ACTIVE
        );
    }

    private CardTransaction postedTransaction(UUID authorizationId, UUID accountId) {
        CardTransaction transaction = CardTransaction.pending(
                "ntx-001",
                authorizationId,
                "card-123",
                accountId,
                money("100.00"),
                NOW.minusSeconds(1)
        );
        transaction.markPosted(NOW);
        return transaction;
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
