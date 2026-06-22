package com.minicard.infrastructure.web.error;

import java.time.Instant;

/**
 * 统一错误响应 DTO。
 *
 * <p>关键词：错误响应, API 错误码, 时间戳, error response,
 * API error, timestamp, エラーレスポンス, エラーコード。</p>
 */
// 错误响应用 record 固定 code/message/timestamp；如果各 handler 返回不同 Map，客户端很难稳定解析。
public record ErrorResponse(
        /** 机器可读错误码。 */
        String code,
        /** 人类可读错误信息。 */
        String message,
        /** API 层生成错误响应的时间。 */
        Instant timestamp
) {
}
