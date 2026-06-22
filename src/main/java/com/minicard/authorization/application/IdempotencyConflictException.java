package com.minicard.authorization.application;

/**
 * Authorization 幂等键冲突异常。
 *
 * <p>关键词：幂等冲突, request fingerprint, 重复请求, idempotency conflict,
 * duplicate request, 冪等衝突(べきとうしょうとつ), 重複依頼(じゅうふくいらい)。</p>
 */
public class IdempotencyConflictException extends RuntimeException {

    /**
     * 固定错误信息，API 层会映射成 409 Conflict。
     */
    public IdempotencyConflictException() {
        // 用专门异常类型比字符串判断可靠；GlobalExceptionHandler 可以稳定映射到 409。
        // 如果混成 IllegalArgumentException，客户端会分不清请求格式错和幂等键复用冲突。
        super("idempotency key was already used with a different request");
    }
}
