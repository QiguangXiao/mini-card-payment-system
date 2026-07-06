package com.minicard.repayment.infrastructure.bank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 银行扣款 Feign client（口座振替 API 边界）。
 *
 * <p>关键词：银行扣款, Feign, HTTP 边界, bank debit client,
 * account transfer API, 口座振替(こうざふりかえ), HTTP連携(HTTPれんけい)。</p>
 *
 * <p>当前 url 默认指向本应用内的 {@code SimulatedBankController}，但调用走完整的
 * HTTP/JSON/Feign 链路，因此 connect/read timeout 由
 * {@code spring.cloud.openfeign.client.config.bank-debit} 配置并真实生效。
 * 接真实银行（或行内扣款网关）时只改 base URL，调用方式不变。</p>
 *
 * <p>诚实的注脚：真实的口座振替多是"提交扣款文件 + 异步回执"的批处理形态，
 * 同步 debit API 是教学简化。这里保留同步形态是为了演示资金侧外部调用的
 * 超时/熔断/幂等防护，异步回执模型留作 credit-card-domain 文档里的扩展话题。</p>
 *
 * <p>Feign 自身保持默认不重试（{@code Retryer.NEVER_RETRY}）。银行扣款也不做
 * R4j 进程内 retry：DelayJob 已是带退避的 durable retry 层，双层重试会相乘；
 * 且每次尝试都是一次资金操作请求，宁可保持低频（对比 notification：provider
 * 调用便宜所以叠了 3 次内存级快速重试）。</p>
 */
@FeignClient(
        name = "bank-debit",
        url = "${repayment.auto-debit.bank-base-url}",
        configuration = BankDebitFeignConfiguration.class
)
public interface BankDebitClient {

    /**
     * 请求银行按幂等键扣款一笔。
     */
    // @PostMapping 在 Feign interface 上描述 outbound 请求：POST {bank-base-url}/simulated-bank/debits。
    @PostMapping("/simulated-bank/debits")
    BankDebitApiResponse debit(@RequestBody BankDebitApiRequest request);

    /**
     * 银行扣款 HTTP 请求契约。
     *
     * <p>字段是和银行侧约定的 JSON contract，不是内部对象：idempotencyKey 是银行侧
     * 去重的依据（同 key 最多实扣一笔），statementId/creditAccountId 是业务 reference。</p>
     */
    record BankDebitApiRequest(
            /** 银行侧幂等键；DelayJob retry 复用同一 key，银行凭它保证不重复出金。 */
            String idempotencyKey,
            /** 要扣款的 statement id，作为银行请求的业务 reference。 */
            UUID statementId,
            /** 客户信用账户 id，未来关联扣款账户授权。 */
            UUID creditAccountId,
            /** 扣款金额数值。 */
            BigDecimal amount,
            /** ISO 货币代码；金额和币种分开传，避免序列化 Money 内部结构。 */
            String currency,
            /** 计划扣款日（支払日），用于银行请求和对账。 */
            LocalDate dueDate
    ) {
    }

    /**
     * 银行扣款 HTTP 回执契约。
     */
    record BankDebitApiResponse(
            /** SUCCESS / FAILED；业务性拒绝（如余额不足）用 FAILED 表达，不是 HTTP 错误。 */
            String status,
            /** FAILED 时的原因；SUCCESS 时为 null。 */
            String failureReason
    ) {
    }
}
