package com.minicard.repayment.application;

/**
 * 自动扣款 outcome，区别“真正入账”和“幂等跳过”。
 *
 * <p>关键词：自动扣款结果, 成功, 幂等跳过, auto repayment outcome,
 * succeeded, idempotent skip, 自動引き落とし結果(じどうひきおとしけっか),
 * 成功(せいこう), 冪等スキップ(べきとうスキップ)。</p>
 */
public enum AutoRepaymentOutcome {
    /** 银行扣款成功，且已生成 repayment。 */
    SUCCEEDED,
    /** statement 已经还清，重复 job 不需要再次扣款。 */
    ALREADY_PAID
}
