package com.minicard.delayjob;

/**
 * DelayJob 的永久失败信号：handler 确定重试不会成功时抛出，job 直接进 DEAD。
 *
 * <p>关键词：永久失败, 快速 DEAD, permanent failure, fast dead,
 * 恒久的失敗(こうきゅうてきしっぱい)。</p>
 *
 * <p>对齐 notification 投递侧的 permanent 路径（provider 4xx → markPermanentFailed →
 * 直接 DEAD）：三个 claimable job 家族里，"确定性失败不烧重试预算"是同一条原则。
 * 例：AUTO_REPAYMENT 收到银行 4xx（契约/配置错误），退避重试同一请求毫无意义，
 * 不如立刻 DEAD 让人工排查——否则要空转 maxAttempts 次才能到达同一终态。</p>
 *
 * <p>只用于确定性失败。瞬态失败（超时、5xx、锁等待）应抛普通异常走 retry/backoff。</p>
 */
public class DelayJobPermanentException extends RuntimeException {

    public DelayJobPermanentException(String message) {
        super(message);
    }

    public DelayJobPermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}
