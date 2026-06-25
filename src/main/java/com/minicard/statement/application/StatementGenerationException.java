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

    public static StatementGenerationException retryable(String message) {
        return new StatementGenerationException(message, true);
    }

    public static StatementGenerationException rejected(String message) {
        return new StatementGenerationException(message, false);
    }

    public boolean retryable() {
        return retryable;
    }
}
