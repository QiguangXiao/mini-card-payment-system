package com.minicard.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.minicard.notification.application.delivery.NotificationDeliveryPermanentException;
import feign.Request;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.junit.jupiter.api.Test;

class NotificationProviderFeignConfigurationTest {

    private final ErrorDecoder decoder =
            new NotificationProviderFeignConfiguration.NotificationProviderErrorDecoder();

    @Test
    // 测试目的：验证 provider 4xx 被分类为 permanent failure。
    // variant：400 Bad Request 不应进入 R4j retry/durable retry，而应让 worker 直接 DEAD。
    void decodesFourHundredAsPermanentFailure() {
        Exception exception = decoder.decode("NotificationProviderClient#send", response(400));

        assertThat(exception).isInstanceOf(NotificationDeliveryPermanentException.class);
        assertThat(exception).hasMessageContaining("status=400");
    }

    @Test
    // 测试目的：验证 5xx 保持 transient provider failure 语义。
    // variant：500 交给 Feign 默认 decoder，后续由 R4j retry + delivery retry/DEAD 处理。
    void leavesFiveHundredAsFeignException() {
        Exception exception = decoder.decode("NotificationProviderClient#send", response(500));

        assertThat(exception).isNotInstanceOf(NotificationDeliveryPermanentException.class);
    }

    private Response response(int status) {
        return Response.builder()
                .status(status)
                .reason("status-" + status)
                .request(Request.create(
                        Request.HttpMethod.POST,
                        "/simulated-provider/notifications/send",
                        Map.of(),
                        null,
                        StandardCharsets.UTF_8,
                        null
                ))
                .headers(Map.of())
                .build();
    }
}
