package com.minicard.transaction.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.infrastructure.web.error.GlobalExceptionHandler;
import com.minicard.transaction.application.PostingService;
import com.minicard.transaction.domain.CardTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Presentment HTTP adapter 的 MVC slice 测试。
 *
 * <p>关键词：入账 API, 清算请求, 输入校验, presentment API,
 * posting contract, MVC slice, 売上データAPI(うりあげデータエーピーアイ)。</p>
 *
 * <p>本类只验证 HTTP JSON 到 PostPresentmentCommand 的边界；networkTransactionId 幂等、
 * authorization/account row lock 和 posting 状态转换由 PostingServiceTest 负责。</p>
 */
@WebMvcTest(PresentmentController.class)
@Import(GlobalExceptionHandler.class)
class PresentmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostingService postingService;

    @Test
    // 测试目的：固定 presentment 成功后的交易响应，尤其是外部 networkTransactionId 与 POSTED 时间。
    void postsPresentment() throws Exception {
        CardTransaction transaction = postedTransaction();
        when(postingService.post(any())).thenReturn(transaction);

        mockMvc.perform(post("/api/presentments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkTransactionId": "ntx-001",
                                  "authorizationId": "fb6933e2-20ea-4268-b1c2-21c6705b1884",
                                  "amount": 100,
                                  "currency": "JPY"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.networkTransactionId").value("ntx-001"))
                .andExpect(jsonPath("$.authorizationId")
                        .value("fb6933e2-20ea-4268-b1c2-21c6705b1884"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.postedAt").exists());
    }

    @Test
    // 测试目的：币种大小写属于 HTTP contract，非法输入必须在开启 posting transaction 前被拦住。
    void rejectsLowercaseCurrencyAtHttpBoundary() throws Exception {
        mockMvc.perform(post("/api/presentments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "networkTransactionId": "ntx-001",
                                  "authorizationId": "fb6933e2-20ea-4268-b1c2-21c6705b1884",
                                  "amount": 100,
                                  "currency": "jpy"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // HTTP contract 不合法时不能进入 posting transaction；否则会无意义地获取 row lock。
        verifyNoInteractions(postingService);
    }

    private CardTransaction postedTransaction() {
        CardTransaction transaction = CardTransaction.pending(
                "ntx-001",
                UUID.fromString("fb6933e2-20ea-4268-b1c2-21c6705b1884"),
                "card-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                Instant.parse("2026-06-19T00:00:00Z")
        );
        transaction.markPosted(Instant.parse("2026-06-19T00:00:01Z"));
        return transaction;
    }
}
