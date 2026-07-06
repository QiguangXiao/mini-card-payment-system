package com.minicard.repayment.infrastructure.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

import com.minicard.repayment.application.BankDebitPermanentException;
import com.minicard.repayment.application.BankDebitRequest;
import com.minicard.repayment.application.BankDebitResult;
import com.minicard.shared.domain.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BankDebitGatewayAdapter 的响应映射与 fallback 分类测试。
 *
 * <p>核心保证：三类失败走三条路——业务拒绝(200+FAILED)返回 failed 结果交给 DelayJob 退避重试；
 * 4xx permanent 从 fallback 重抛（最终快速 DEAD）；瞬态(熔断打开/超时)转成 failed 结果，绝不入账。</p>
 */
class BankDebitGatewayAdapterTest {

    @Test
    void successResponseMapsToSuccessfulResult() {
        BankDebitClient client = mock(BankDebitClient.class);
        BankDebitGatewayAdapter adapter = new BankDebitGatewayAdapter(client);
        when(client.debit(any()))
                .thenReturn(new BankDebitClient.BankDebitApiResponse("SUCCESS", null));

        BankDebitResult result = adapter.debit(request());

        assertThat(result.successful()).isTrue();
    }

    @Test
    void failedResponseMapsToBusinessFailureWithReason() {
        BankDebitClient client = mock(BankDebitClient.class);
        BankDebitGatewayAdapter adapter = new BankDebitGatewayAdapter(client);
        when(client.debit(any()))
                .thenReturn(new BankDebitClient.BankDebitApiResponse("FAILED", "insufficient balance"));

        BankDebitResult result = adapter.debit(request());

        // 业务性拒绝不是异常：failed 结果让 AutoRepaymentService 抛 AutoRepaymentFailedException，
        // DelayJob 按退避重试——客户补足余额后就能成功。
        assertThat(result.successful()).isFalse();
        assertThat(result.failureReason()).isEqualTo("insufficient balance");
    }

    @Test
    void permanentFallbackRethrowsSoDelayJobCanFastDead() {
        BankDebitGatewayAdapter adapter = new BankDebitGatewayAdapter(mock(BankDebitClient.class));
        BankDebitPermanentException permanent =
                new BankDebitPermanentException("bank rejected debit request status=400");

        // 4xx 契约错误不能被吞成 failed 结果，否则会按瞬态失败烧光 maxAttempts 次退避。
        assertThatThrownBy(() -> adapter.fallback(request(), permanent))
                .isSameAs(permanent);
    }

    @Test
    void transientFallbackFailsSafeWithoutPosting() {
        BankDebitGatewayAdapter adapter = new BankDebitGatewayAdapter(mock(BankDebitClient.class));

        BankDebitResult result = adapter.fallback(request(), new IllegalStateException("read timeout"));

        // 不知道银行状态时绝不入账：failed 结果让 DelayJob 重试，幂等键保证银行侧不重复出金。
        assertThat(result.successful()).isFalse();
        assertThat(result.failureReason()).contains("unavailable");
    }

    @Test
    void circuitOpenFallbackReportsBreakerState() {
        BankDebitGatewayAdapter adapter = new BankDebitGatewayAdapter(mock(BankDebitClient.class));
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("bankDebit");

        BankDebitResult result = adapter.fallback(
                request(),
                CallNotPermittedException.createCallNotPermittedException(breaker)
        );

        assertThat(result.successful()).isFalse();
        assertThat(result.failureReason()).contains("circuit breaker is open");
    }

    private BankDebitRequest request() {
        return new BankDebitRequest(
                "auto-debit:" + UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new Money(new BigDecimal("5000.00"), Currency.getInstance("JPY")),
                LocalDate.parse("2026-07-27")
        );
    }
}
