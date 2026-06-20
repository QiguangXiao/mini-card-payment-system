package com.minicard.repayment.application;

/**
 * 还款请求被业务规则拒绝的异常。
 *
 * <p>关键词：还款拒绝, 业务异常, 金额校验, repayment rejection,
 * business exception, amount validation, 入金拒否(にゅうきんきょひ),
 * 業務例外(ぎょうむれいがい)。</p>
 *
 * <p>它表示请求本身不合法，例如币种不一致、金额超过剩余应还额，不属于并发冲突。</p>
 */
public class RepaymentRejectedException extends RuntimeException {

    /**
     * 保存可返回给调用方的拒绝原因。
     */
    public RepaymentRejectedException(String message) {
        super(message);
    }
}
