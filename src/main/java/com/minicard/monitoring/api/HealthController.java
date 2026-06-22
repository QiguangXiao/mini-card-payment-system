package com.minicard.monitoring.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轻量公开 liveness endpoint。
 *
 * <p>关键词：健康检查, liveness, HTTP endpoint, health check,
 * monitoring, ヘルスチェック, 生存確認(せいぞんかくにん)。</p>
 *
 * <p>这个 endpoint 只证明 HTTP 应用正在运行。依赖健康、metrics 和运维诊断应交给
 * Spring Boot Actuator，而不是在这个 controller 里重复实现。</p>
 */
// @RestController 保证返回值写成 JSON body；如果用 @Controller，"OK" 这类返回值可能被当成 view name。
@RestController
@RequestMapping("/api")
public class HealthController {

    /**
     * 返回应用存活状态。
     */
    @GetMapping("/health")
    public HealthResponse health() {
        return HealthResponse.ok();
    }
}
