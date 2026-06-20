package com.minicard.repayment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 银行自动扣款模拟配置。
 *
 * <p>关键词：自动扣款配置, 模拟银行结果, auto debit properties,
 * simulated bank result, 口座振替設定(こうざふりかえせってい),
 * 銀行結果シミュレーション(ぎんこうけっかシミュレーション)。</p>
 *
 * <p>默认 SUCCESS 让主流程可运行；切成 FAILED 可以演示扣款失败路径，
 * 但暂不引入真实银行网络、清算文件或失败通知。</p>
 */
@ConfigurationProperties(prefix = "repayment.auto-debit")
public record AutoDebitProperties(
        /** 本地模拟的银行扣款结果（bank debit result / 口座振替結果）。 */
        BankDebitStatus simulatedResult,
        /** 模拟失败时的错误原因，后续可以进入通知或人工处理。 */
        String failureReason
) {

    public AutoDebitProperties {
        if (simulatedResult == null) {
            // 默认 SUCCESS 让主业务路径可跑通；FAILED 分支保留给失败恢复练习。
            simulatedResult = BankDebitStatus.SUCCESS;
        }
        if (failureReason == null || failureReason.isBlank()) {
            // failureReason 不能空，否则 DelayJob DEAD/日志没有可排查信息。
            failureReason = "simulated bank debit failure";
        }
    }
}
