package com.minicard.infrastructure.web.ratelimit;

/**
 * API 入口限流超限异常，由 GlobalExceptionHandler 映射为 429 Too Many Requests。
 *
 * <p>关键词：限流超限, 429, Retry-After, rate limit exceeded,
 * too many requests, 流量超過(りゅうりょうちょうか)。</p>
 *
 * <p>选择"interceptor 抛异常 → advice 统一映射"而不是在 interceptor 里直接写 response：
 * 429 的 JSON body 结构、日志和其他错误码保持同一条出口路径，客户端拿到的错误契约一致。</p>
 */
public class RateLimitExceededException extends RuntimeException {

    /** 客户端应等待的秒数，写入 Retry-After header（HTTP 规范要求整数秒）。 */
    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Too many requests, retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
