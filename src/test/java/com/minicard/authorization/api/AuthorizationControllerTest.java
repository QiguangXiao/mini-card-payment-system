package com.minicard.authorization.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.authorization.application.IdempotencyConflictException;
import com.minicard.authorization.application.AuthorizationService;
import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.shared.domain.Money;
import com.minicard.infrastructure.web.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization HTTP contract 的 MVC slice 测试。
 *
 * <p>关键词：授权 API, Bean Validation, 错误契约, authorization API,
 * idempotency conflict, MVC slice, オーソリAPI, 入力検証(にゅうりょくけんしょう)。</p>
 *
 * <p>这里 mock application service，刻意只验证 controller adapter、JSON DTO、HTTP status、
 * request-id filter 和 GlobalExceptionHandler 的组合；额度锁和幂等 claim 由 application/IT 测试负责。</p>
 */
@WebMvcTest(AuthorizationController.class)
@Import(GlobalExceptionHandler.class)
class AuthorizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorizationService authorizationService;

    @Test
    // 测试目的：固定成功授权的 request/response JSON contract，并确认请求链生成 X-Request-Id。
    // variant：application 返回 APPROVED aggregate，HTTP 层应拆出金额/币种/时间，不能暴露内部 Money/Optional。
    void createsAuthorization() throws Exception {
        when(authorizationService.authorize(any())).thenReturn(approvedAuthorization());

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 100,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.cardId").value("card-123"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.currency").value("JPY"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.declineReason").doesNotExist())
                .andExpect(jsonPath("$.decidedAt").exists());
    }

    @Test
    // 测试目的：DECLINED 是正常业务结果而不是 HTTP 异常，仍返回 200 并携带稳定 declineReason。
    // 反事实：若把拒绝统一映射成 4xx，调用方会把发卡决策误当成请求格式失败。
    void returnsDeclinedAuthorizationWithReason() throws Exception {
        when(authorizationService.authorize(any())).thenReturn(declinedAuthorization());

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 200000,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason")
                        .value("SINGLE_TRANSACTION_LIMIT_EXCEEDED"));
    }

    @Test
    // 测试目的：金额为 0 在 HTTP boundary 被 Bean Validation 拒绝为 400 INVALID_REQUEST。
    void rejectsInvalidAmount() throws Exception {
        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 0,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    // 测试目的：同 Idempotency-Key 代表不同请求时，专用异常必须稳定映射成 409。
    // 客户端应依赖 IDEMPOTENCY_CONFLICT code，而不是解析可能变化的 message。
    void returnsConflictWhenIdempotencyKeyIsReusedForDifferentRequest() throws Exception {
        when(authorizationService.authorize(any())).thenThrow(new IdempotencyConflictException());

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 200,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    // 测试目的：GET 查询只暴露 AuthorizationResponse contract，不触发任何状态推进。
    void getsAuthorization() throws Exception {
        Authorization authorization = approvedAuthorization();
        when(authorizationService.get(authorization.id())).thenReturn(authorization);

        mockMvc.perform(get("/api/authorizations/{id}", authorization.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(authorization.id().toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    // 测试目的：application 的明确资源不存在信号映射成 404 RESOURCE_NOT_FOUND。
    void returnsNotFoundForUnknownAuthorization() throws Exception {
        UUID id = UUID.fromString("8f2d8907-0471-4209-9862-73e09f62cd1f");
        when(authorizationService.get(id))
                .thenThrow(new NoSuchElementException("authorization not found: " + id));

        mockMvc.perform(get("/api/authorizations/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    // 测试目的：未预期异常只返回通用 500，数据库/stack trace 等内部细节只能留在 server log。
    void hidesUnexpectedInternalErrorDetails() throws Exception {
        when(authorizationService.authorize(any()))
                .thenThrow(new RuntimeException("database connection details"));

        mockMvc.perform(post("/api/authorizations")
                        .header("Idempotency-Key", "key-internal-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cardId": "card-123",
                                  "amount": 100,
                                  "currency": "JPY",
                                  "merchantId": "merchant-123",
                                  "merchantCountry": "JP",
                                  "cardholderCountry": "JP"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    private Authorization approvedAuthorization() {
        return Authorization.restore(
                UUID.fromString("fb6933e2-20ea-4268-b1c2-21c6705b1884"),
                "fingerprint-1",
                "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                AuthorizationStatus.APPROVED,
                null,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"),
                Instant.parse("2026-06-14T00:00:01Z"),
                null,
                null
        );
    }

    private Authorization declinedAuthorization() {
        return Authorization.restore(
                UUID.fromString("9e5958ad-8be0-44dc-bded-da06199c4a73"),
                "fingerprint-2",
                "card-123",
                new Money(new BigDecimal("200000.00"), Currency.getInstance("JPY")),
                AuthorizationStatus.DECLINED,
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"),
                null,
                null,
                null
        );
    }
}
