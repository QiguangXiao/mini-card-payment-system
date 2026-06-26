package com.minicard.repayment.infrastructure.bank;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import com.minicard.repayment.application.AutoDebitProperties;
import com.minicard.repayment.application.BankDebitGateway;
import com.minicard.repayment.application.BankDebitRequest;
import com.minicard.repayment.application.BankDebitResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 本地模拟银行扣款 gateway。
 *
 * <p>关键词：模拟银行, 自动扣款, 失败路径, simulated bank gateway,
 * auto debit, failure path, 銀行シミュレーション(ぎんこうシミュレーション),
 * 口座振替(こうざふりかえ), 失敗経路(しっぱいけいろ)。</p>
 *
 * <p>真实系统会提交银行扣款文件或调用银行 API，再异步接收 success/failure result。
 * 当前不建 bank account 表，先假设客户已有默认扣款授权；默认 SUCCESS，
 * 保留 FAILED 分支用于后续演示扣款失败、通知和逾期处理。</p>
 */
@Component
@RequiredArgsConstructor
public class SimulatedBankDebitGateway implements BankDebitGateway {

    /** 控制本地模拟返回 SUCCESS 或 FAILED，便于演示 failure path。 */
    private final AutoDebitProperties properties;

    /**
     * 按幂等键缓存已执行的扣款结果，模拟银行侧的 at-most-once 去重。
     * 真实银行/清算系统会用持久化的请求 reference 去重；这里用进程内 map 表达同一语义
     * （进程重启会遗忘，对模拟可接受）。如果不去重，DelayJob retry 会重复从客户账户出金。
     */
    private final Map<String, BankDebitResult> executedDebits = new ConcurrentHashMap<>();

    /**
     * 返回预设银行扣款结果。
     *
     * <p>这里不访问外部网络，避免把学习项目变成真实 integration；重点是保留 bank debit result
     * 这条业务分支，以及“同一幂等键最多实扣一笔”的去重语义。</p>
     */
    @Override
    public BankDebitResult debit(BankDebitRequest request) {
        // 已成功扣款的 key 直接复用首次结果，绝不二次出金。
        BankDebitResult cached = executedDebits.get(request.idempotencyKey());
        if (cached != null) {
            return cached;
        }
        BankDebitResult result = execute();
        if (result.successful()) {
            // 只缓存成功：成功必须 at-most-once；失败不缓存，让客户补足余额后 DelayJob retry 能再次尝试。
            // 这里假设同一 statement 的扣款由单个 DelayJob worker 串行触发（claim 后 PROCESSING），
            // 因此 get-then-put 之间不会有同 key 并发；真实 gateway 由银行侧持久去重保证原子性。
            executedDebits.put(request.idempotencyKey(), result);
        }
        return result;
    }

    private BankDebitResult execute() {
        // 默认返回成功；把 repayment.auto-debit.simulated-success 设成 false 可演示失败路径。
        if (!properties.simulatedSuccess()) {
            // 失败必须显式返回原因，让 DelayJob 和日志有排查线索。
            return BankDebitResult.failed(properties.failureReason());
        }
        return BankDebitResult.success();
    }
}
