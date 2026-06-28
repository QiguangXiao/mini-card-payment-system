package com.minicard.repayment.infrastructure.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

import com.minicard.repayment.application.AutoDebitProperties;
import com.minicard.repayment.application.BankDebitRequest;
import com.minicard.repayment.application.BankDebitResult;
import com.minicard.shared.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SimulatedBankDebitGateway 的幂等去重行为测试。
 *
 * <p>核心保证：同一 idempotencyKey 成功扣款只发生一次（DelayJob retry 不重复出金），
 * 但失败不被缓存，客户补足余额后 retry 仍能再次尝试。</p>
 *
 * <p>用对象身份证明去重：成功结果被缓存，二次调用复用同一实例（{@code isSameAs}）；
 * 失败不缓存，每次都是新执行的新实例（{@code isNotSameAs}）。</p>
 */
class SimulatedBankDebitGatewayTest {

    private static final String KEY = "auto-debit:" + UUID.randomUUID();

    @Test
    void successfulDebitIsDedupedAcrossRetriesWithSameKey() {
        SimulatedBankDebitGateway gateway = gateway(true);

        BankDebitResult first = gateway.debit(request(KEY));
        BankDebitResult second = gateway.debit(request(KEY));

        assertThat(first.successful()).isTrue();
        // 同一 key 第二次复用缓存的首次结果 → 没有再次实扣。
        assertThat(second).isSameAs(first);
    }

    @Test
    void differentKeysDebitIndependently() {
        SimulatedBankDebitGateway gateway = gateway(true);

        BankDebitResult a = gateway.debit(request("auto-debit:" + UUID.randomUUID()));
        BankDebitResult b = gateway.debit(request("auto-debit:" + UUID.randomUUID()));

        // 不同 key 各自独立扣款，是不同的执行结果。
        assertThat(a.successful()).isTrue();
        assertThat(b.successful()).isTrue();
        assertThat(b).isNotSameAs(a);
    }

    @Test
    void failedDebitIsNotCachedSoRetryCanReattempt() {
        SimulatedBankDebitGateway gateway = gateway(false);

        BankDebitResult first = gateway.debit(request(KEY));
        BankDebitResult second = gateway.debit(request(KEY));

        assertThat(first.successful()).isFalse();
        assertThat(second.successful()).isFalse();
        // 失败不缓存：第二次是重新执行的新结果，证明 retry 能再次尝试。
        assertThat(second).isNotSameAs(first);
    }

    private SimulatedBankDebitGateway gateway(boolean simulatedSuccess) {
        return new SimulatedBankDebitGateway(
                new AutoDebitProperties(simulatedSuccess, "insufficient bank balance"));
    }

    private BankDebitRequest request(String idempotencyKey) {
        return new BankDebitRequest(
                idempotencyKey,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new Money(new BigDecimal("1500"), Currency.getInstance("JPY")),
                LocalDate.parse("2026-07-27")
        );
    }
}
