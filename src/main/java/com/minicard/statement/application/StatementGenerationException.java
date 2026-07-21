package com.minicard.statement.application;

/**
 * Statement 生成阶段的统一业务异常。
 *
 * <p>关键词：账单生成异常, retryable, rejected, statement generation exception,
 * idempotency, 請求作成例外(せいきゅうさくせいれいがい)。</p>
 *
 * <p>mini-card 不需要一组很细的异常层级。这里用 retryable 标记区分“稍后可重试”
 * 和“本账户本周期可跳过”，让 job handler 的失败处理保持直观。</p>
 */
public class StatementGenerationException extends RuntimeException {

    private final boolean retryable;

    private StatementGenerationException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    /**
     * 创建“整个 statement job 需要重试”的失败信号。
     *
     * <p>适合瞬态数据库/锁故障。StatementJobHandler 会计入 failed，dispatcher 再依据
     * maxAttempts 把分片放回 PENDING 或推进 DEAD；如果误标成 rejected，这个账户会被永久跳过。</p>
     */
    public static StatementGenerationException retryable(String message) {
        return new StatementGenerationException(message, true);
    }

    /**
     * 创建“不需要重试该账户”的确定性业务跳过信号。
     *
     * <p>例如账户在 worker 真正加锁读取时已经没有 UNBILLED 交易。它会计入 skipped，
     * 不是系统故障；如果仍按 retryable 处理，空账户会反复消耗整个分片的 retry budget。</p>
     */
    public static StatementGenerationException rejected(String message) {
        return new StatementGenerationException(message, false);
    }

    /**
     * 返回 dispatcher 是否应重试整个分片，而不是表示当前单账户方法能在原事务内立即重试。
     */
    public boolean retryable() {
        return retryable;
    }
}
