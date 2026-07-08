package com.minicard.repayment.infrastructure.bank;

import com.minicard.repayment.application.BankDebitGateway;
import com.minicard.repayment.application.BankDebitPermanentException;
import com.minicard.repayment.application.BankDebitRequest;
import com.minicard.repayment.application.BankDebitResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 银行扣款的 infrastructure adapter。
 *
 * <p>关键词：银行扣款, CircuitBreaker, fail-safe, bank debit gateway,
 * 口座振替(こうざふりかえ), 回復性(かいふくせい)。</p>
 *
 * <p>AutoRepaymentService 依赖 {@link BankDebitGateway} port；本 adapter 才知道
 * Feign DTO、HTTP endpoint、熔断和错误分类。包约定：{@code infrastructure/bank}
 * 放我方出站 client + adapter（按机制命名，对齐 risk/gateway、notification/delivery），
 * {@code infrastructure/external} 只放模拟第三方的 server。</p>
 *
 * <p>R4j 组合刻意与另外两家不同（选择规则见 spring-java-technical-learning §10.3）：</p>
 * <pre>
 * - CircuitBreaker: 要。银行网关宕机时快速失败，让 DelayJob 尽早退避，
 *   而不是每个 job 吊死在 read timeout 上占满 worker 线程。
 * - Retry: 不要。DelayJob 已是带退避的 durable retry 层；且每次尝试都是资金操作请求。
 * - RateLimiter: 现在不要。自动还款由 DelayJob 到期批量驱动，默认频率远低于 notification provider
 *   这类高频出站调用；为了"组件齐全"硬加会多一层拒绝/退避语义。若真实银行有明确 TPS 契约，
 *   再按银行配额和 pod 数配置出站限流，并同样避免把本地节流误记成银行 brownout。
 * - Bulkhead: 不要。调用方跑在有界的 DelayJob worker pool 里，池本身就是并发上限
 *   （对比 risk：跑在授权请求线程里才需要 bulkhead 保护 Hikari）。
 * </pre>
 *
 * <p>超时二义性与幂等：银行可能已实扣但回执超时丢失（at-least-once 的经典场景）。
 * 这里不做任何"猜测成功"的处理——超时按失败返回，DelayJob retry 复用同一个
 * idempotencyKey，银行侧凭 key 去重回放首次结果，所以不会重复出金，也不会漏账。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BankDebitGatewayAdapter implements BankDebitGateway {

    private final BankDebitClient bankDebitClient;

    @Override
    // @CircuitBreaker 依赖 Spring AOP proxy。fallback 按异常类型精确分派（见下面两个重载）。
    // 实例名 bankDebit 对应 application.yml 的 resilience4j.circuitbreaker.instances.bankDebit。
    @CircuitBreaker(name = "bankDebit", fallbackMethod = "fallback")
    public BankDebitResult debit(BankDebitRequest request) {
        BankDebitClient.BankDebitApiResponse response = bankDebitClient.debit(
                new BankDebitClient.BankDebitApiRequest(
                        request.idempotencyKey(),
                        request.statementId(),
                        request.creditAccountId(),
                        request.amount().amount(),
                        request.amount().currency().getCurrencyCode(),
                        request.dueDate()
                )
        );
        if (!"SUCCESS".equals(response.status())) {
            // 业务性拒绝（余额不足等）：HTTP 层成功，业务层失败。
            // 这是可恢复失败——不缓存、不熔断统计意义上的"错误"，交给 DelayJob durable retry。
            return BankDebitResult.failed(
                    response.failureReason() == null
                            ? "bank declined debit"
                            : response.failureReason()
            );
        }
        return BankDebitResult.success();
    }

    /**
     * permanent 专用 fallback：R4j 按异常类型选择最具体的重载，4xx 契约错误直接重抛。
     *
     * <p>不能把 permanent 吞成 failed 结果：那会让 DelayJob 按瞬态失败退避重试，
     * 烧光重试次数才 DEAD。重抛后由 AUTO_REPAYMENT handler 翻译成
     * DelayJobPermanentException，一次就进 DEAD。</p>
     */
    @SuppressWarnings("unused")
    public BankDebitResult fallback(BankDebitRequest request, BankDebitPermanentException exception) {
        throw exception;
    }

    /**
     * 瞬态 fallback：熔断打开、超时、5xx、连接失败都转成 failed 结果。
     *
     * <p>失败结果会让 AutoRepaymentService 抛 AutoRepaymentFailedException，
     * DelayJob 按退避重试——这就是"不知道银行状态时绝不入账"的 fail-safe 路径。</p>
     */
    @SuppressWarnings("unused")
    public BankDebitResult fallback(BankDebitRequest request, Throwable throwable) {
        String reason = throwable instanceof CallNotPermittedException
                ? "bank debit circuit breaker is open"
                : "bank debit gateway unavailable: " + throwable.getMessage();
        log.warn(
                "bank_debit_unavailable statementId={} idempotencyKey={} reason={}",
                request.statementId(),
                request.idempotencyKey(),
                reason
        );
        return BankDebitResult.failed(reason);
    }
}
