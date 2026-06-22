package com.minicard.risk.infrastructure.external;

import java.util.concurrent.ThreadLocalRandom;

import com.minicard.risk.application.RiskProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地模拟第三方 risk API，让 Feign 调用链在本项目内可学习、可运行。
 */
// 这里虽然在同一个 Spring Boot 应用里，但仍走 HTTP/JSON/Feign 边界。
// 如果直接 mock Java 方法，就学不到 timeout、序列化、circuit breaker 这些第三方集成问题。
@RestController
@RequiredArgsConstructor
public class SimulatedExternalRiskController {

    private final RiskProperties properties;

    @PostMapping("/external-risk/assess")
    public ExternalRiskClient.ExternalRiskResponse assess(
            @RequestBody ExternalRiskClient.ExternalRiskRequest request
    ) {
        simulateLatency();
        maybeFail();
        int score = score(request);
        return new ExternalRiskClient.ExternalRiskResponse(
                score < properties.external().highRiskScoreThreshold(),
                score
        );
    }

    private void simulateLatency() {
        try {
            Thread.sleep(properties.external().simulatedLatencyMillis());
        } catch (InterruptedException exception) {
            // Java 并发习惯：捕获 InterruptedException 后恢复 interrupt flag。
            // 如果吞掉中断，应用关闭时线程可能继续睡眠/工作，影响优雅停机。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("external risk call was interrupted", exception);
        }
    }

    private void maybeFail() {
        int failureRate = properties.external().failureRatePercent();
        if (failureRate > 0 && ThreadLocalRandom.current().nextInt(100) < failureRate) {
            throw new IllegalStateException("simulated external risk failure");
        }
    }

    private int score(ExternalRiskClient.ExternalRiskRequest request) {
        int score = 10;
        if (!request.merchantCountry().equals(request.cardholderCountry())) {
            score += 35;
        }
        if (request.amount().intValue() >= 30000) {
            score += 25;
        }
        if (request.merchantId().contains("new")) {
            score += 15;
        }
        return Math.min(score, 100);
    }
}
