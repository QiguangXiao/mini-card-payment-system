package com.minicard.repayment.infrastructure.external;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.minicard.repayment.application.AutoDebitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 本地模拟银行扣款 API，让 bank debit 的 Feign 调用链在本项目内可学习、可运行。
 *
 * <p>关键词：模拟银行, 口座振替, 幂等去重, simulated bank,
 * account transfer, idempotent debit, 銀行シミュレーション(ぎんこうシミュレーション),
 * 口座振替(こうざふりかえ)。</p>
 *
 * <p>包约定：{@code infrastructure/external} 只放"假装是第三方"的模拟 server
 * （对齐 risk/notification）；我方出站 client + adapter 在 {@code infrastructure/bank}。</p>
 *
 * <p>这里虽然在同一个 Spring Boot 应用里，但仍走 HTTP/JSON/Feign 边界：
 * 超时、序列化、熔断这些第三方集成问题都真实生效。真实系统会提交银行扣款文件
 * 或调用银行 API，再异步接收 success/failure 回执；当前不建 bank account 表，
 * 先假设客户已有默认扣款授权。</p>
 */
@RestController
@RequiredArgsConstructor
public class SimulatedBankController {

    /** 控制模拟银行返回 SUCCESS 或 FAILED（业务性拒绝），便于演示失败路径。 */
    private final AutoDebitProperties properties;

    /**
     * 按幂等键缓存已执行的扣款结果，模拟银行侧的 at-most-once 去重。
     * 真实银行/清算系统会用持久化的请求 reference 去重；这里用进程内 map 表达同一语义
     * （进程重启会遗忘，对模拟可接受）。如果不去重，DelayJob retry 会重复从客户账户出金。
     */
    private final Map<String, DebitResponse> executedDebits = new ConcurrentHashMap<>();

    /**
     * 模拟银行扣款端点：契约错误回 400，业务拒绝回 200+FAILED，成功按幂等键去重。
     */
    @PostMapping("/simulated-bank/debits")
    public DebitResponse debit(@RequestBody DebitRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.amount() == null || request.amount().signum() <= 0) {
            // 契约坏了（缺幂等键、金额非正）回 400。调用方的 ErrorDecoder 会把它翻译成
            // BankDebitPermanentException → DelayJob 快速 DEAD，不按瞬态失败空转重试。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid bank debit request");
        }
        // 已成功扣款的 key 直接复用首次结果，绝不二次出金。
        DebitResponse cached = executedDebits.get(request.idempotencyKey());
        if (cached != null) {
            return cached;
        }
        DebitResponse result = execute();
        if ("SUCCESS".equals(result.status())) {
            // 只缓存成功：成功必须 at-most-once；失败不缓存，让客户补足余额后 DelayJob retry 能再次尝试。
            // 这里假设同一 statement 的扣款由单个 DelayJob worker 串行触发（claim 后 PROCESSING），
            // 因此 get-then-put 之间不会有同 key 并发；真实银行由持久化去重保证原子性。
            executedDebits.put(request.idempotencyKey(), result);
        }
        return result;
    }

    private DebitResponse execute() {
        // 默认返回成功；把 repayment.auto-debit.simulated-success 设成 false 可演示业务拒绝路径。
        if (!properties.simulatedSuccess()) {
            // 业务性失败（如余额不足）用 200+FAILED 表达，不是 HTTP 错误——它是可恢复的，
            // 和 4xx 契约错误走完全不同的重试路径。
            return new DebitResponse("FAILED", properties.failureReason());
        }
        return new DebitResponse("SUCCESS", null);
    }

    /**
     * 银行侧的请求 DTO。字段与调用方 BankDebitApiRequest 的 JSON 对齐，
     * 但刻意是独立定义——模拟"两个系统各自持有契约"，而不是共享内部对象。
     */
    record DebitRequest(
            String idempotencyKey,
            UUID statementId,
            UUID creditAccountId,
            BigDecimal amount,
            String currency,
            LocalDate dueDate
    ) {
    }

    /**
     * 银行侧的回执 DTO；status=SUCCESS/FAILED，failureReason 仅失败时有值。
     */
    record DebitResponse(
            String status,
            String failureReason
    ) {
    }
}
