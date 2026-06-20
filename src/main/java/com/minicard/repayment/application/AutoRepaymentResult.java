package com.minicard.repayment.application;

import java.util.UUID;

/**
 * 自动扣款执行结果。
 *
 * <p>关键词：自动扣款结果, 入账, 已还清, auto repayment result,
 * posting, already paid, 自動引き落とし結果(じどうひきおとしけっか),
 * 入金処理(にゅうきんしょり), 支払い済み(しはらいずみ)。</p>
 *
 * <p>DelayJob handler 主要依赖异常判断重试；这个 result 用于测试和未来审计日志表达业务 outcome。</p>
 */
public record AutoRepaymentResult(
        /** 业务结果：成功入账或已还清幂等跳过。 */
        AutoRepaymentOutcome outcome,
        /** 被处理的 statement id。 */
        UUID statementId,
        /** 成功入账时生成的 repayment id；already paid 时为空。 */
        UUID repaymentId
) {

    /**
     * 银行扣款成功且 repayment posting 成功。
     */
    public static AutoRepaymentResult succeeded(UUID statementId, UUID repaymentId) {
        return new AutoRepaymentResult(AutoRepaymentOutcome.SUCCEEDED, statementId, repaymentId);
    }

    /**
     * DelayJob 到达时账单已经被其他还款还清，按 idempotency 视为完成。
     */
    public static AutoRepaymentResult alreadyPaid(UUID statementId) {
        return new AutoRepaymentResult(AutoRepaymentOutcome.ALREADY_PAID, statementId, null);
    }
}
