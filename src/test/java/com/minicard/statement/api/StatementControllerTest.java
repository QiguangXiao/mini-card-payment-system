package com.minicard.statement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.infrastructure.web.error.GlobalExceptionHandler;
import com.minicard.statement.application.read.StatementReadModel;
import com.minicard.statement.application.read.StatementReadService;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementLineSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatementController.class)
@Import(GlobalExceptionHandler.class)
class StatementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatementReadService statementReadService;

    @Test
    void doesNotExposeManualStatementGeneration() throws Exception {
        // 出账只能由 billing-cycle jobs 触发；防止以后又无意把普通 HTTP backfill 入口加回来。
        mockMvc.perform(post("/api/statements/generate"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void getsStatement() throws Exception {
        Statement statement = statement();
        when(statementReadService.get(statement.id())).thenReturn(StatementReadModel.from(statement));

        mockMvc.perform(get("/api/statements/{id}", statement.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(statement.id().toString()))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void returnsNotFoundForUnknownStatement() throws Exception {
        UUID id = UUID.randomUUID();
        when(statementReadService.get(id))
                .thenThrow(new NoSuchElementException("statement not found: " + id));

        mockMvc.perform(get("/api/statements/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private Statement statement() {
        Statement statement = Statement.close(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-25"),
                List.of(
                        transaction("ntx-001", "1000.00"),
                        transaction("ntx-002", "500.00")
                ),
                money("1000.00"),
                Instant.parse("2026-07-01T00:00:00Z")
        );
        return statement;
    }

    private StatementLineSource transaction(String networkTransactionId, String amount) {
        return new StatementLineSource(
                UUID.randomUUID(),
                networkTransactionId,
                UUID.randomUUID(),
                "card-123",
                money(amount),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
