package com.minicard.transaction.application;

/**
 * Presentment 幂等冲突异常。
 *
 * <p>关键词：presentment 冲突, networkTransactionId, 重复请求,
 * presentment conflict, idempotency conflict, 売上データ重複(うりあげデータじゅうふく),
 * 重複依頼(じゅうふくいらい)。</p>
 */
public class PresentmentConflictException extends RuntimeException {

    /**
     * 固定错误信息，API 层会映射成 409 Conflict。
     */
    public PresentmentConflictException() {
        super("networkTransactionId was reused for a different presentment request");
    }
}
