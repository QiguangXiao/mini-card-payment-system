package com.minicard.repayment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.infrastructure.web.error.GlobalExceptionHandler;
import com.minicard.repayment.application.RepaymentRejectedException;
import com.minicard.repayment.application.RepaymentService;
import com.minicard.repayment.domain.Repayment;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Repayment HTTP contract 的 MVC slice 测试。
 *
 * <p>关键词：还款 API, 幂等请求, 业务冲突, repayment API,
 * error contract, MVC slice, 入金API(にゅうきんエーピーアイ)。</p>
 *
 * <p>这里验证 header/body 绑定和 ErrorResponse 映射；statement/account 的锁顺序、余额扣减和
 * after-commit cache eviction 由 RepaymentServiceTest 覆盖。</p>
 */
@WebMvcTest(RepaymentController.class)
@Import(GlobalExceptionHandler.class)
class RepaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepaymentService repaymentService;

    @Test
    // 测试目的：固定还款成功响应，包括 statement/account 关联、金额和 RECEIVED 时间。
    void receivesRepayment() throws Exception {
        Repayment repayment = receivedRepayment();
        when(repaymentService.receive(any())).thenReturn(repayment);

        mockMvc.perform(post("/api/repayments")
                        .header("Idempotency-Key", "rp-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "statementId": "22222222-2222-2222-2222-222222222222",
                                  "amount": 500,
                                  "currency": "JPY"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statementId")
                        .value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.creditAccountId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").exists());
    }

    @Test
    // 测试目的：锁后业务校验拒绝必须映射成 409 REPAYMENT_REJECTED，而不是客户端格式错误 400。
    void returnsConflictWhenRepaymentIsRejected() throws Exception {
        when(repaymentService.receive(any()))
                .thenThrow(new RepaymentRejectedException("repayment amount exceeds statement remaining amount"));

        mockMvc.perform(post("/api/repayments")
                        .header("Idempotency-Key", "rp-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "statementId": "22222222-2222-2222-2222-222222222222",
                                  "amount": 2000,
                                  "currency": "JPY"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPAYMENT_REJECTED"));
    }

    @Test
    // 测试目的：按 repayment id 查询已入账结果，不重新执行资金动作。
    void getsRepayment() throws Exception {
        Repayment repayment = receivedRepayment();
        when(repaymentService.get(repayment.id())).thenReturn(repayment);

        mockMvc.perform(get("/api/repayments/{id}", repayment.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(repayment.id().toString()))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    // 测试目的：不存在的 repayment 使用统一 404 contract，避免 controller 自己拼装错误响应。
    void returnsNotFoundForUnknownRepayment() throws Exception {
        UUID id = UUID.randomUUID();
        when(repaymentService.get(id)).thenThrow(new NoSuchElementException("repayment not found: " + id));

        mockMvc.perform(get("/api/repayments/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private Repayment receivedRepayment() {
        Repayment repayment = Repayment.pending(
                "rp-key-001",
                "fingerprint",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                money("500.00"),
                Instant.parse("2026-07-10T00:00:00Z")
        );
        repayment.markReceived(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-07-10T00:00:01Z")
        );
        return repayment;
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
