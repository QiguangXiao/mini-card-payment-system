package com.minicard.monitoring.api;

/**
 * 轻量 health API 的稳定响应契约。
 *
 * <p>关键词：健康检查, 响应契约, liveness, health response,
 * public contract, ヘルスチェック, 生存確認(せいぞんかくにん)。</p>
 */
public record HealthResponse(String status) {

    /**
     * 返回 OK 状态。
     */
    public static HealthResponse ok() {
        return new HealthResponse("OK");
    }
}
