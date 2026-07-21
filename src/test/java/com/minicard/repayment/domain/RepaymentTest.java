package com.minicard.repayment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repayment aggregate 最小状态转换测试。
 *
 * <p>关键词：还款状态, 入账完成, repayment aggregate,
 * PENDING to RECEIVED, 入金状態(にゅうきんじょうたい)。</p>
 */
class RepaymentTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @Test
    // winner 完成资金入账后，repayment 必须同时记录 accountId 和 receivedAt，形成可审计结果。
    void marksRepaymentReceived() {
        UUID statementId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Repayment repayment = Repayment.pending(
                "rp-key-001",
                "fingerprint",
                statementId,
                money("500.00"),
                NOW
        );

        repayment.markReceived(accountId, NOW.plusSeconds(1));

        assertThat(repayment.status()).isEqualTo(RepaymentStatus.RECEIVED);
        assertThat(repayment.creditAccountId()).contains(accountId);
        assertThat(repayment.receivedAt()).contains(NOW.plusSeconds(1));
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
