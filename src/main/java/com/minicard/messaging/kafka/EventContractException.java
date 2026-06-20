package com.minicard.messaging.kafka;

/**
 * 永久性的消息契约错误，不应该消耗业务 retry capacity。
 *
 * <p>关键词：消息契约, 永久失败, 消费者校验, event contract,
 * permanent failure, consumer validation, メッセージ契約(メッセージけいやく),
 * 恒久失敗(こうきゅうしっぱい)。</p>
 */
public class EventContractException extends RuntimeException {

    /**
     * 用于缺字段、header mismatch 这类不可重试错误。
     */
    public EventContractException(String message) {
        super(message);
    }

    /**
     * 用于 JSON parse 失败等需要保留原始 cause 的错误。
     */
    public EventContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
