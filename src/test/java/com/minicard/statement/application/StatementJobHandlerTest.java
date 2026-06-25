package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minicard.statement.domain.StatementBatch;
import com.minicard.statement.domain.StatementBatchStatus;
import com.minicard.statement.domain.StatementJob;
import com.minicard.statement.domain.StatementJobExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatementJobHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final UUID BATCH_ID =
            UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID GENERATED_ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SKIPPED_ACCOUNT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RETRY_ACCOUNT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private StatementBatchRepository batchRepository;
    private StatementBillingRepository billingRepository;
    private StatementGenerationService statementGenerationService;
    private StatementJobHandler handler;

    @BeforeEach
    void setUp() {
        batchRepository = mock(StatementBatchRepository.class);
        billingRepository = mock(StatementBillingRepository.class);
        statementGenerationService = mock(StatementGenerationService.class);
        handler = new StatementJobHandler(
                batchRepository,
                billingRepository,
                statementGenerationService,
                statementProperties()
        );
    }

    @Test
    void countsRejectedAccountsAsSkippedAndRetryableAccountsAsFailed() {
        StatementJob job = StatementJob.pending(BATCH_ID, 0, 4, NOW);
        when(batchRepository.findById(BATCH_ID)).thenReturn(batch());
        when(billingRepository.findAccountIdsForJob(
                Instant.parse("2026-05-31T15:00:00Z"),
                Instant.parse("2026-06-30T15:00:00Z"),
                0,
                4
        )).thenReturn(List.of(GENERATED_ACCOUNT_ID, SKIPPED_ACCOUNT_ID, RETRY_ACCOUNT_ID));
        doThrow(StatementGenerationException.rejected("no billable transactions"))
                .when(statementGenerationService)
                .generate(argThat(command -> command.creditAccountId().equals(SKIPPED_ACCOUNT_ID)));
        doThrow(StatementGenerationException.retryable("ledger not ready"))
                .when(statementGenerationService)
                .generate(argThat(command -> command.creditAccountId().equals(RETRY_ACCOUNT_ID)));

        StatementJobExecutionResult result = handler.handle(job);

        assertThat(result.processedAccountCount()).isEqualTo(3);
        assertThat(result.generatedStatementCount()).isEqualTo(1);
        assertThat(result.skippedAccountCount()).isEqualTo(1);
        assertThat(result.failedAccountCount()).isEqualTo(1);
    }

    private StatementBatch batch() {
        return StatementBatch.restore(
                BATCH_ID,
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-27"),
                StatementBatchStatus.RUNNING,
                3,
                1000,
                4,
                NOW,
                null,
                null
        );
    }

    private StatementProperties statementProperties() {
        return new StatementProperties(
                new StatementProperties.Batch(true, "0 0 1 * * *", "Asia/Tokyo", 31, 27, 1000),
                new StatementProperties.Jobs(true, 1000, 10000, 10, 3, 300, 4, 20),
                new StatementProperties.Policy(
                        new BigDecimal("0.10"),
                        Map.of("JPY", new BigDecimal("1000.00"))
                )
        );
    }
}
