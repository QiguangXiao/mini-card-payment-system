package com.minicard.creditaccount.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CreditAccount 的额度守恒和资金余额不变式测试。
 *
 * <p>关键词：可用额度, 额度预占, 已入账余额, credit account,
 * reservation, posted balance, 利用可能枠(りようかのうわく)。</p>
 *
 * <p>核心公式是 {@code available = limit - reserved - posted}；每个状态转换都必须保持金额不为负、
 * 不超过授信额度，并区分业务拒绝结果与真正的非法状态异常。</p>
 */
class CreditAccountTest {

    @Test
    // 额度充足时 reserve 只增加 reserved，不应提前增加 posted balance。
    void reservesAvailableCredit() {
        CreditAccount account = account("1000.00", "200.00", CreditAccountStatus.ACTIVE);

        CreditReservationResult result = account.reserve(money("300.00"));

        assertThat(result.reserved()).isTrue();
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("500.00");
        assertThat(account.availableCredit().amount()).isEqualByComparingTo("500.00");
    }

    @Test
    // 超过 available credit 返回业务拒绝，并保持原余额完全不变。
    void rejectsReservationExceedingAvailableCredit() {
        CreditAccount account = account("1000.00", "950.00", CreditAccountStatus.ACTIVE);

        CreditReservationResult result = account.reserve(money("100.00"));

        assertThat(result.optionalFailure())
                .contains(CreditReservationFailure.INSUFFICIENT_AVAILABLE_CREDIT);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("950.00");
    }

    @Test
    // BLOCKED account 不能新增 reservation，即使数值额度充足。
    void rejectsReservationForBlockedAccount() {
        CreditAccount account = account("1000.00", "0.00", CreditAccountStatus.BLOCKED);

        CreditReservationResult result = account.reserve(money("100.00"));

        assertThat(result.optionalFailure()).contains(CreditReservationFailure.ACCOUNT_BLOCKED);
    }

    @Test
    // 不同币种不能参与同一个额度公式，避免把 JPY 与 USD 数字直接相加减。
    void rejectsReservationInDifferentCurrency() {
        CreditAccount account = account("1000.00", "0.00", CreditAccountStatus.ACTIVE);
        Money usd = new Money(new BigDecimal("100.00"), Currency.getInstance("USD"));

        CreditReservationResult result = account.reserve(usd);

        assertThat(result.optionalFailure()).contains(CreditReservationFailure.CURRENCY_MISMATCH);
    }

    @Test
    // authorization expiry 会释放 reserved，并恢复同等 available credit。
    void releasesPreviouslyReservedCredit() {
        CreditAccount account = account("1000.00", "300.00", CreditAccountStatus.ACTIVE);

        account.release(money("100.00"));

        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("200.00");
        assertThat(account.availableCredit().amount()).isEqualByComparingTo("800.00");
    }

    @Test
    // presentment posting 是 reserved → posted 的内部搬移，不能重复占用总额度。
    void postsAuthorizedAmountFromReservedToPostedBalance() {
        CreditAccount account = account("1000.00", "300.00", CreditAccountStatus.ACTIVE);

        account.postAuthorized(money("100.00"));

        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("200.00");
        assertThat(account.postedBalance().amount()).isEqualByComparingTo("100.00");
        assertThat(account.availableCredit().amount()).isEqualByComparingTo("700.00");
    }

    @Test
    // repayment 只减少 posted balance，从而恢复 available credit，不影响仍有效的 reservation。
    void appliesRepaymentToPostedBalance() {
        CreditAccount account = accountWithPosted("1000.00", "100.00", "300.00");

        account.applyRepayment(money("120.00"));

        assertThat(account.postedBalance().amount()).isEqualByComparingTo("180.00");
        assertThat(account.availableCredit().amount()).isEqualByComparingTo("720.00");
    }

    @Test
    // 还款不能把 posted balance 扣成负数；上层锁后校验之外，aggregate 仍保留最后防线。
    void rejectsRepaymentAbovePostedBalance() {
        CreditAccount account = accountWithPosted("1000.00", "0.00", "100.00");

        assertThatThrownBy(() -> account.applyRepayment(money("120.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds posted balance");
    }

    private CreditAccount account(String limit, String reserved, CreditAccountStatus status) {
        return CreditAccount.restore(
                UUID.randomUUID(),
                money(limit),
                money(reserved),
                status
        );
    }

    private CreditAccount accountWithPosted(String limit, String reserved, String posted) {
        return CreditAccount.restore(
                UUID.randomUUID(),
                money(limit),
                money(reserved),
                money(posted),
                CreditAccountStatus.ACTIVE
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
