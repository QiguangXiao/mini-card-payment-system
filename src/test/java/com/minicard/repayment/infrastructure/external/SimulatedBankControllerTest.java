package com.minicard.repayment.infrastructure.external;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.minicard.repayment.application.AutoDebitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模拟银行 controller 的幂等去重与错误分类行为测试。
 *
 * <p>核心保证（沿自被替换的 in-process stub）：同一 idempotencyKey 成功扣款只发生一次
 * （DelayJob retry 不重复出金），失败不缓存让 retry 能再次尝试；
 * 新增：契约坏请求回 400，由调用方 ErrorDecoder 翻译成 permanent。</p>
 */
class SimulatedBankControllerTest {

    private static final String KEY = "auto-debit:" + UUID.randomUUID();

    @Test
    void successfulDebitIsDedupedAcrossRetriesWithSameKey() {
        SimulatedBankController controller = controller(true);

        SimulatedBankController.DebitResponse first = controller.debit(request(KEY));
        SimulatedBankController.DebitResponse second = controller.debit(request(KEY));

        assertThat(first.status()).isEqualTo("SUCCESS");
        // 同一 key 第二次复用缓存的首次结果 → 银行侧没有再次实扣。
        assertThat(second).isSameAs(first);
    }

    @Test
    void differentKeysDebitIndependently() {
        SimulatedBankController controller = controller(true);

        SimulatedBankController.DebitResponse a = controller.debit(request("auto-debit:" + UUID.randomUUID()));
        SimulatedBankController.DebitResponse b = controller.debit(request("auto-debit:" + UUID.randomUUID()));

        assertThat(a.status()).isEqualTo("SUCCESS");
        assertThat(b.status()).isEqualTo("SUCCESS");
        assertThat(b).isNotSameAs(a);
    }

    @Test
    void failedDebitIsNotCachedSoRetryCanReattempt() {
        SimulatedBankController controller = controller(false);

        SimulatedBankController.DebitResponse first = controller.debit(request(KEY));
        SimulatedBankController.DebitResponse second = controller.debit(request(KEY));

        // 业务性失败（余额不足）不缓存：客户补足余额后（配置翻回 true）retry 仍能实扣。
        assertThat(first.status()).isEqualTo("FAILED");
        assertThat(first.failureReason()).isEqualTo("insufficient balance");
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void malformedRequestIsRejectedWithBadRequest() {
        SimulatedBankController controller = controller(true);

        // 缺幂等键：银行侧无法去重，必须拒绝而不是"好心"处理——这正是 permanent 失败的来源。
        assertThatThrownBy(() -> controller.debit(request(" ")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode()
                ).isEqualTo(HttpStatus.BAD_REQUEST));

        // 金额非正同理：4xx 由调用方 ErrorDecoder 翻译成 BankDebitPermanentException。
        assertThatThrownBy(() -> controller.debit(new SimulatedBankController.DebitRequest(
                KEY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.ZERO,
                "JPY",
                LocalDate.parse("2026-07-27")
        ))).isInstanceOf(ResponseStatusException.class);
    }

    private SimulatedBankController controller(boolean simulatedSuccess) {
        return new SimulatedBankController(
                new AutoDebitProperties(simulatedSuccess, "insufficient balance")
        );
    }

    private SimulatedBankController.DebitRequest request(String idempotencyKey) {
        return new SimulatedBankController.DebitRequest(
                idempotencyKey,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("5000.00"),
                "JPY",
                LocalDate.parse("2026-07-27")
        );
    }
}
