package com.minicard.risk.infrastructure.gateway;

import com.minicard.risk.application.ExternalRiskGateway;
import com.minicard.risk.domain.RiskAssessmentRequest;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 外部风控的 infrastructure adapter。
 *
 * <p>关键词：外部风控, Feign, CircuitBreaker, Bulkhead, external risk gateway,
 * fail-closed, brownout, 外部審査(がいぶしんさ)。</p>
 *
 * <p>RiskAssessmentService 依赖 ExternalRiskGateway port；这个 adapter 才知道
 * Feign DTO、HTTP endpoint、timeout/bulkhead/circuit breaker 和 fallback 指标。</p>
 *
 * <p>基础背景：CircuitBreaker 根据近期调用结果在 CLOSED/OPEN/HALF_OPEN 间切换；OPEN 时不再调用
 * provider，直接进入 fallback。Semaphore Bulkhead 则只允许固定数量的并发线程进入外部调用，
 * 满时立即拒绝。前者按“近期是否健康”保护，后者按“现在有多少并发”保护，职责不同。</p>
 *
 * <p>包约定：{@code infrastructure/gateway} 放我方的出站 client + adapter；
 * {@code infrastructure/external} 只放模拟第三方的 server（对齐 notification 的
 * delivery/external 划分）。adapter 实现的是 application 层 port，"给内部用"是它的本职。</p>
 */
@Component
@RequiredArgsConstructor
public class ExternalRiskGatewayAdapter implements ExternalRiskGateway {

    private final ExternalRiskClient externalRiskClient;
    private final MeterRegistry meterRegistry;

    @Override
    // @CircuitBreaker/@Bulkhead 是声明式写法：Spring AOP 会在 bean 外生成 proxy，调用本方法前后执行保护逻辑。
    // 因此必须从其他 bean 调用这个 public 方法；同类 self-invocation 会绕过 proxy。fallback signature
    // 必须保留原参数并追加 Throwable，R4j 才能在失败时找到它。
    // Bulkhead 用 semaphore，而不是线程池隔离：当前调用仍在 authorization transaction 线程内，
    // 如果丢到另一个线程，Spring transaction/thread-bound connection 语义会变得很难解释。
    // maxConcurrentCalls 要小于 Hikari pool 的可用余量，防止外部风控 brownout 把连接池全部钉住。
    @CircuitBreaker(name = "externalRisk", fallbackMethod = "fallback")
    @Bulkhead(name = "externalRisk", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "fallback")
    public RiskDecision assess(RiskAssessmentRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "exception";
        try {
            // Feign client 调用模拟第三方 risk API；CircuitBreaker/Bulkhead fallback 采用 fail-closed。
            ExternalRiskClient.ExternalRiskResponse response = externalRiskClient.assess(
                    new ExternalRiskClient.ExternalRiskRequest(
                            request.cardId(),
                            request.merchantId(),
                            request.merchantCountry(),
                            request.cardholderCountry(),
                            request.amount().amount(),
                            request.amount().currency().getCurrencyCode()
                    )
            );
            if (!response.approved()) {
                outcome = "declined";
                return RiskDecision.decline(RiskDeclineReason.EXTERNAL_RISK_DECLINED, response.score());
            }
            outcome = "approved";
            return RiskDecision.approve(response.score());
        } finally {
            sample.stop(meterRegistry.timer("risk.external.latency", "outcome", outcome));
        }
    }

    @SuppressWarnings("unused")
    public RiskDecision fallback(RiskAssessmentRequest request, Throwable throwable) {
        String reason = fallbackReason(throwable);
        meterRegistry.counter("risk.external.fallback", "reason", reason).increment();
        if (throwable instanceof BulkheadFullException) {
            meterRegistry.counter("risk.external.bulkhead.rejected").increment();
        }
        // 外部风控不可用、断路器打开或 bulkhead 满时都 fail-closed，避免在无法判断风险时继续批准授权。
        // 这和 Redis velocity 的 fail-open 不同：external risk 是最终风险判定依赖，velocity 是辅助软信号。
        return RiskDecision.decline(RiskDeclineReason.EXTERNAL_RISK_UNAVAILABLE, 100);
    }

    private String fallbackReason(Throwable throwable) {
        if (throwable instanceof BulkheadFullException) {
            return "bulkhead_full";
        }
        if (throwable instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        return "exception";
    }
}
