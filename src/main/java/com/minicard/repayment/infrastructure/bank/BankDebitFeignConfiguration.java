package com.minicard.repayment.infrastructure.bank;

import com.minicard.repayment.application.BankDebitPermanentException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

/**
 * bank-debit Feign client 的错误分类配置。
 *
 * <p>关键词：Feign ErrorDecoder, 扣款错误分类, permanent vs transient,
 * error classification, エラー分類(エラーぶんるい)。</p>
 *
 * <p>银行扣款的失败必须分三类，走三条不同的路：</p>
 * <pre>
 * 1. 业务性拒绝（余额不足等）: HTTP 200 + status=FAILED —— 可恢复，
 *    客户补足余额后能成功，交给 DelayJob durable retry（带退避）。
 * 2. 契约/配置错误: HTTP 4xx —— 确定性失败，本类翻译成 BankDebitPermanentException，
 *    DelayJob 直接 DEAD，不烧重试次数。
 * 3. 银行网关不可用: 5xx/timeout/连接失败 —— 瞬态，Feign 默认异常 + 熔断器
 *    统计，adapter fallback 转成 failed 结果，DelayJob retry。
 * </pre>
 *
 * <p>本类<b>故意不标 @Configuration</b>（同 NotificationProviderFeignConfiguration）：
 * 标了会被 component scan 扫成全局 Bean，这里的 4xx 翻译会泄漏进 risk/notification
 * 的 Feign client。只被 {@code @FeignClient(configuration=...)} 指名时，
 * 它才进入 bank-debit client 的子容器。</p>
 */
public class BankDebitFeignConfiguration {

    @Bean
    ErrorDecoder bankDebitErrorDecoder() {
        return new BankDebitErrorDecoder();
    }

    static class BankDebitErrorDecoder implements ErrorDecoder {

        private final ErrorDecoder defaultDecoder = new ErrorDecoder.Default();

        @Override
        public Exception decode(String methodKey, Response response) {
            int status = response.status();
            if (status >= 400 && status < 500) {
                // 4xx 是 permanent failure：请求格式错、账户标识无效、凭证失效。
                // 重试同一请求不会变好；进入 DelayJob 快速 DEAD 路径，交给人工排查。
                return new BankDebitPermanentException(
                        "bank rejected debit request status=" + status + " method=" + methodKey);
            }
            // 5xx/其他交给 Feign 默认 decoder 变成 FeignException；连接/超时由 HTTP client 抛出。
            // 这些瞬态异常由 bankDebit 熔断器统计，并在 adapter fallback 里转成 failed 结果。
            return defaultDecoder.decode(methodKey, response);
        }
    }
}
