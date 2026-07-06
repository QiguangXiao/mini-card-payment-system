package com.minicard.repayment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 银行自动扣款模拟配置。
 *
 * <p>关键词：自动扣款配置, 模拟银行结果, auto debit properties,
 * simulated bank result, 口座振替設定(こうざふりかえせってい),
 * 銀行結果シミュレーション(ぎんこうけっかシミュレーション)。</p>
 *
 * <p>默认成功让主流程可运行；把 simulated-success 设成 false 可以演示扣款失败路径
 * （DelayJob retry / DEAD），但暂不引入真实银行网络、清算文件或失败通知。</p>
 */
// @ConfigurationProperties 把 repayment.auto-debit.* 绑定成 typed record，供模拟银行 controller 注入。
@ConfigurationProperties(prefix = "repayment.auto-debit")
public record AutoDebitProperties(
        /** 本地模拟的银行扣款是否成功（口座振替結果）。 */
        Boolean simulatedSuccess,
        /** 模拟失败时的错误原因，后续可以进入通知或人工处理。 */
        String failureReason
) {

    // compact constructor 在 Spring 绑定后执行，做默认值归一化。
    // 这里用 Boolean（包装类型）而不是 boolean：未配置时基本类型会默认 false，
    // 会把“默认成功”悄悄变成“默认失败”，本地第一笔自动扣款就会走失败路径。
    public AutoDebitProperties {
        if (simulatedSuccess == null) {
            // 默认成功让主业务路径可跑通；false 分支保留给失败恢复练习。
            simulatedSuccess = Boolean.TRUE;
        }
        if (failureReason == null || failureReason.isBlank()) {
            // failureReason 不能空，否则 DelayJob DEAD/日志没有可排查信息。
            failureReason = "simulated bank debit failure";
        }
    }
}
