package com.minicard.repayment.application;

/**
 * 银行扣款的永久失败：请求契约或配置错误（HTTP 4xx），重试同一请求也不会成功。
 *
 * <p>关键词：永久失败, 契约错误, permanent failure, contract error,
 * 恒久的失敗(こうきゅうてきしっぱい)。</p>
 *
 * <p>它和"业务性拒绝"（{@link BankDebitResult#failed}，如余额不足）语义不同：
 * 余额不足是可恢复的——客户补足余额后 DelayJob 重试就能成功；
 * 而 4xx（请求格式错、账户标识无效、凭证失效）是确定性错误，烧掉 durable retry
 * 次数毫无意义，应该让 DelayJob 直接进 DEAD 交给人工排查。</p>
 *
 * <p>由 Feign ErrorDecoder（BankDebitFeignConfiguration）在 HTTP 边界抛出，
 * 穿过 {@link BankDebitGateway} port 后在 AUTO_REPAYMENT 的 DelayJob handler
 * 边界被翻译成框架级 permanent 异常。</p>
 */
public class BankDebitPermanentException extends RuntimeException {

    public BankDebitPermanentException(String message) {
        super(message);
    }
}
