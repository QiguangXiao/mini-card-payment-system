package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.domain.delivery.NotificationDeliveryPermanentException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * notification-provider Feign client 的错误分类配置。
 *
 * <p>关键词：Feign ErrorDecoder, HTTP 错误分类, 不可重试, error decoder,
 * retry classification, HTTP エラー分類(エラーぶんるい)。</p>
 *
 * <p>生产里推荐在 Feign 边界先按 HTTP status 分类：4xx 通常是请求格式、认证、权限、配置等确定性失败；
 * 5xx/timeout/连接失败才更像 transient provider failure。这个配置只挂到 notification-provider client，
 * 不影响 risk 的 external-risk client。</p>
 */
public class NotificationProviderFeignConfiguration {

    @Bean
    ErrorDecoder notificationProviderErrorDecoder() {
        return new NotificationProviderErrorDecoder();
    }

    static class NotificationProviderErrorDecoder implements ErrorDecoder {

        private final ErrorDecoder defaultDecoder = new ErrorDecoder.Default();

        @Override
        public Exception decode(String methodKey, Response response) {
            int status = response.status();
            if (status >= 400 && status < 500) {
                // 4xx 是 permanent failure：R4j 不应 retry，delivery 也应直接 DEAD。
                // 例子：400 请求格式不符、401/403 provider 凭证错误、404 endpoint 配错。
                return new NotificationDeliveryPermanentException(
                        "notification provider rejected request status=" + status + " method=" + methodKey);
            }
            // 其他情况交给 Feign 默认 decoder：5xx 会变成 FeignException，连接/超时类异常也由 Feign/HTTP client 抛出。
            // 这些异常仍可被 ResilientCallHelper 的 Retry + CircuitBreaker 当作 transient failure 处理。
            return defaultDecoder.decode(methodKey, response);
        }
    }
}
