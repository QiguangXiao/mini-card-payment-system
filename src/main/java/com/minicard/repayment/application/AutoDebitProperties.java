package com.minicard.repayment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 银行自动扣款模拟配置。
 *
 * <p>默认 SUCCESS 让主流程可运行；切成 FAILED 可以演示扣款失败路径，
 * 但暂不引入真实银行网络、清算文件或失败通知。</p>
 */
@ConfigurationProperties(prefix = "repayment.auto-debit")
public record AutoDebitProperties(
        BankDebitStatus simulatedResult,
        String failureReason
) {

    public AutoDebitProperties {
        if (simulatedResult == null) {
            simulatedResult = BankDebitStatus.SUCCESS;
        }
        if (failureReason == null || failureReason.isBlank()) {
            failureReason = "simulated bank debit failure";
        }
    }
}
