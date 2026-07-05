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
 *
 * <p>挂载点与生效机制（本类不出现在 yml 里）：它被 NotificationProviderClient 的
 * {@code @FeignClient(configuration = NotificationProviderFeignConfiguration.class)} 属性引用。
 * Spring Cloud OpenFeign 会给每个命名 client 创建独立的子 ApplicationContext，组装代理时按
 * "先查该 client 的子容器，再退回全局默认" 的顺序找 Encoder/Decoder/ErrorDecoder 等组件；
 * 本类的 Bean 只注册进 notification-provider 的子容器，external-risk 查不到它，仍用默认 ErrorDecoder。</p>
 *
 * <p>本类<b>故意不标 @Configuration</b>：标了会被主容器 component scan 扫成全局 Bean，
 * ErrorDecoder 将作用于所有 Feign client，把 4xx 到通知专用异常的翻译泄漏进 risk 链路。
 * 不标注解时，它只在被 @FeignClient 显式指名时进入那个 client 的子容器，隔离才成立。</p>
 *
 * <p>与 yml 的分工：同一个 client 的配置有两条通道，按 client 名字合并——yml 的
 * {@code spring.cloud.openfeign.client.config.notification-provider} 管数字类配置（connect/read timeout），
 * 本类管需要写代码的组件类配置（ErrorDecoder）。</p>
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
