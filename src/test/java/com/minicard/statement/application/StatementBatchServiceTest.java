package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minicard.statement.domain.StatementBatch;
import com.minicard.statement.domain.StatementBatchStatus;
import com.minicard.statement.domain.StatementJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementBatchServiceTest {

    private StatementBatchRepository batchRepository;
    private StatementJobRepository jobRepository;
    private StatementBillingRepository billingRepository;
    private StatementBatchService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(StatementBatchRepository.class);
        jobRepository = mock(StatementJobRepository.class);
        billingRepository = mock(StatementBillingRepository.class);
        service = new StatementBatchService(
                batchRepository,
                jobRepository,
                billingRepository,
                statementProperties(),
                new JapaneseBusinessDayCalendar(),
                Clock.fixed(Instant.parse("2026-06-30T16:00:00Z"), ZoneId.of("Asia/Tokyo"))
        );
    }

    @Test
    void createsBatchAndShardedJobsOnDayAfterCloseDate() {
        when(billingRepository.countBillableAccounts(
                eq(Instant.parse("2026-05-31T15:00:00Z")),
                eq(Instant.parse("2026-06-30T15:00:00Z"))
        )).thenReturn(2500L);
        when(batchRepository.insert(any())).thenReturn(true);

        StatementBatchCreationResult result = service.createDueBatch();

        assertThat(result.due()).isTrue();
        assertThat(result.created()).isTrue();
        assertThat(result.periodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(result.periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(result.dueDate()).isEqualTo(LocalDate.parse("2026-07-27"));
        assertThat(result.accountCount()).isEqualTo(2500);
        assertThat(result.jobCount()).isEqualTo(3);

        ArgumentCaptor<StatementBatch> batch = ArgumentCaptor.forClass(StatementBatch.class);
        verify(batchRepository).insert(batch.capture());
        assertThat(batch.getValue().targetAccountsPerJob()).isEqualTo(1000);
        assertThat(batch.getValue().jobCount()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StatementJob>> jobs = ArgumentCaptor.forClass(List.class);
        verify(jobRepository).insertAll(jobs.capture());
        assertThat(jobs.getValue())
                .hasSize(3)
                .extracting(StatementJob::shardNo)
                .containsExactly(0, 1, 2);
    }

    @Test
    void skipsWhenTodayDoesNotFollowCloseDate() {
        StatementBatchCreationResult result = service.createDueBatch(LocalDate.parse("2026-06-30"));

        assertThat(result.due()).isFalse();
        verify(billingRepository, never()).countBillableAccounts(any(), any());
        verify(batchRepository, never()).insert(any());
        verify(jobRepository, never()).insertAll(any());
    }

    @Test
    void movesDueDateToNextBusinessDayWhenBaseDayIsWeekend() {
        when(billingRepository.countBillableAccounts(
                eq(Instant.parse("2026-07-31T15:00:00Z")),
                eq(Instant.parse("2026-08-31T15:00:00Z"))
        )).thenReturn(1L);
        when(batchRepository.insert(any())).thenReturn(true);

        StatementBatchCreationResult result = service.createDueBatch(LocalDate.parse("2026-09-01"));

        assertThat(result.periodStart()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(result.periodEnd()).isEqualTo(LocalDate.parse("2026-08-31"));
        assertThat(result.dueDate()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(result.jobCount()).isEqualTo(1);
    }

    @Test
    void completesBatchWhenAllJobsFinished() {
        StatementBatch batch = StatementBatch.restore(
                UUID.randomUUID(),
                LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-07-27"),
                StatementBatchStatus.RUNNING,
                1,
                1000,
                1,
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                null
        );
        when(batchRepository.findByIdForUpdate(batch.id())).thenReturn(batch);
        when(jobRepository.summarizeByBatchId(batch.id()))
                .thenReturn(new StatementJobStatusSummary(1, 0, 0, 1, 0));

        service.completeBatchIfAllJobsFinished(batch.id());

        assertThat(batch.status()).isEqualTo(StatementBatchStatus.COMPLETED);
        verify(batchRepository).updateState(batch);
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
