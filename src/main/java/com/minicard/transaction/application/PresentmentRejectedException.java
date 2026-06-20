package com.minicard.transaction.application;

/**
 * Presentment 请求被业务规则拒绝。
 *
 * <p>关键词：入账拒绝, presentment, 业务异常, presentment rejection,
 * business exception, 売上処理エラー(うりあげしょりエラー), 業務例外(ぎょうむれいがい)。</p>
 */
public class PresentmentRejectedException extends RuntimeException {

    /**
     * 保存拒绝原因，API 层会返回给调用方。
     */
    public PresentmentRejectedException(String message) {
        super(message);
    }
}
