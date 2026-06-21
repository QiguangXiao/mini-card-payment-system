package com.minicard.statement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.infrastructure.web.error.GlobalExceptionHandler;
import com.minicard.statement.application.StatementGenerationRejectedException;
import com.minicard.statement.application.StatementReadModel;
import com.minicard.statement.application.StatementReadModelService;
import com.minicard.statement.application.StatementService;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementTransaction;
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

@WebMvcTest(StatementController.class)
@Import(GlobalExceptionHandler.class)
class StatementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatementService statementService;

    @MockitoBean
    private StatementReadModelService statementReadModelService;

    @Test
    void generatesStatement() throws Exception {
        Statement statement = statement();
        when(statementService.generate(any())).thenReturn(statement);

        mockMvc.perform(post("/api/statements/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "creditAccountId": "11111111-1111-1111-1111-111111111111",
                                  "periodStart": "2026-06-01",
                                  "periodEnd": "2026-06-30",
                                  "dueDate": "2026-07-25"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditAccountId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.totalAmount").value(1500.00))
                .andExpect(jsonPath("$.minimumPaymentAmount").value(1000.00))
                .andExpect(jsonPath("$.items[0].networkTransactionId").value("ntx-001"));
    }

    @Test
    void returnsConflictWhenNoTransactionsCanBeBilled() throws Exception {
        when(statementService.generate(any()))
                .thenThrow(new StatementGenerationRejectedException("no unbilled posted transactions"));

        mockMvc.perform(post("/api/statements/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "creditAccountId": "11111111-1111-1111-1111-111111111111",
                                  "periodStart": "2026-06-01",
                                  "periodEnd": "2026-06-30",
                                  "dueDate": "2026-07-25"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATEMENT_GENERATION_REJECTED"));
    }

    @Test
    void getsStatement() throws Exception {
        Statement statement = statement();
        when(statementReadModelService.get(statement.id())).thenReturn(StatementReadModel.from(statement));

        mockMvc.perform(get("/api/statements/{id}", statement.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(statement.id().toString()))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void returnsNotFoundForUnknownStatement() throws Exception {
        UUID id = UUID.randomUUID();
        when(statementReadModelService.get(id))
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
        statement.pullDomainEvents();
        return statement;
    }

    private StatementTransaction transaction(String networkTransactionId, String amount) {
        return new StatementTransaction(
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
