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

@WebMvcTest(RepaymentController.class)
@Import(GlobalExceptionHandler.class)
class RepaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepaymentService repaymentService;

    @Test
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
    void getsRepayment() throws Exception {
        Repayment repayment = receivedRepayment();
        when(repaymentService.get(repayment.id())).thenReturn(repayment);

        mockMvc.perform(get("/api/repayments/{id}", repayment.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(repayment.id().toString()))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
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
                money("500.00"),
                money("1000.00"),
                Instant.parse("2026-07-10T00:00:01Z")
        );
        repayment.pullDomainEvents();
        return repayment;
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
