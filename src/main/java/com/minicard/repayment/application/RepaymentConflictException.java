package com.minicard.repayment.application;

/**
 * 还款幂等键冲突异常。
 *
 * <p>关键词：幂等冲突, request fingerprint, 重复请求, idempotency conflict,
 * duplicate request, 入金リクエスト重複(にゅうきんリクエストじゅうふく),
 * 重複依頼(じゅうふくいらい)。</p>
 *
 * <p>同一个 idempotency key 如果对应不同请求体，必须拒绝，避免调用方误把不同还款合并成一次。</p>
 */
public class RepaymentConflictException extends RuntimeException {

    /**
     * 固定错误信息，便于 API 层映射为 409 Conflict。
     */
    public RepaymentConflictException() {
        super("repayment idempotency key was already used with a different request");
    }
}
