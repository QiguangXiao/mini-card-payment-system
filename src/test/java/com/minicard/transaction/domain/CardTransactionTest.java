package com.minicard.transaction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.transaction.domain.event.CardTransactionPostedDomainEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CardTransaction 的 presentment、posting 和 statement assignment 状态机测试。
 *
 * <p>关键词：卡交易, 入账状态, 出账标记, card transaction,
 * presentment idempotency, billing assignment, 売上確定(うりあげかくてい)。</p>
 */
class CardTransactionTest {

    private static final Instant NOW = Instant.parse("2026-06-19T00:00:00Z");

    @Test
    // PENDING presentment 完成入账后进入 POSTED + UNBILLED，并产生一次 posted domain event。
    void pendingTransactionCanBeMarkedPosted() {
        CardTransaction transaction = transaction();

        transaction.markPosted(NOW.plusSeconds(1));

        assertThat(transaction.status()).isEqualTo(CardTransactionStatus.POSTED);
        assertThat(transaction.billingStatus()).isEqualTo(CardTransactionBillingStatus.UNBILLED);
        assertThat(transaction.postedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(transaction.pullDomainEvents())
                .hasSize(1)
                .first()
                .isInstanceOf(CardTransactionPostedDomainEvent.class);
    }

    @Test
    // 相同 authorization 与金额代表同一 presentment retry，可直接返回已有交易而不重复入账。
    void detectsSamePresentmentForIdempotentRetry() {
        CardTransaction transaction = transaction();

        assertThat(transaction.samePresentment(
                transaction.authorizationId(),
                money("100.00")
        )).isTrue();
    }

    @Test
    // POSTED transaction 只能分配给一张 statement；第二次分配必须拒绝以防重复出账。
    void postedTransactionCanBeAssignedToStatementOnce() {
        CardTransaction transaction = transaction();
        transaction.markPosted(NOW.plusSeconds(1));
        UUID statementId = UUID.randomUUID();

        transaction.assignToStatement(statementId, NOW.plusSeconds(2));

        assertThat(transaction.statementId()).contains(statementId);
        assertThat(transaction.billingStatus()).isEqualTo(CardTransactionBillingStatus.BILLED);
        assertThat(transaction.statementAssignedAt()).contains(NOW.plusSeconds(2));
        assertThatThrownBy(() -> transaction.assignToStatement(UUID.randomUUID(), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    // 尚未完成 posting 的交易不能进入账单，否则 statement 会包含未确认的资金事实。
    void pendingTransactionCannotBeAssignedToStatement() {
        CardTransaction transaction = transaction();

        assertThatThrownBy(() -> transaction.assignToStatement(UUID.randomUUID(), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only posted");
    }

    private CardTransaction transaction() {
        return CardTransaction.pending(
                "ntx-001",
                UUID.randomUUID(),
                "card-123",
                UUID.randomUUID(),
                money("100.00"),
                NOW
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
