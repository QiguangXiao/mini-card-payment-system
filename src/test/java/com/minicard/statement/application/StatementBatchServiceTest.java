package com.minicard.statement.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementBatchServiceTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private StatementBatchRepository batchRepository;
    private StatementService statementService;
    private StatementBatchService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(StatementBatchRepository.class);
        statementService = mock(StatementService.class);
        service = new StatementBatchService(
                batchRepository,
                statementService,
                new StatementBatchProperties(true, 60000, 31, 27, 100),
                new JapaneseBusinessDayCalendar(),
                Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void runsBatchOnDayAfterCloseDate() {
        when(batchRepository.findCreditAccountIdsWithUnbilledPostedTransactions(
                eq(Instant.parse("2026-06-01T00:00:00Z")),
                eq(Instant.parse("2026-07-01T00:00:00Z")),
                eq(100)
        )).thenReturn(List.of(ACCOUNT_ID));

        StatementBatchResult result = service.runDueBatch();

        assertThat(result.due()).isTrue();
        assertThat(result.periodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(result.periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(result.dueDate()).isEqualTo(LocalDate.parse("2026-07-27"));
        assertThat(result.generatedCount()).isEqualTo(1);
        ArgumentCaptor<GenerateStatementCommand> command =
                ArgumentCaptor.forClass(GenerateStatementCommand.class);
        verify(statementService).generate(command.capture());
        assertThat(command.getValue().creditAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(command.getValue().periodStart()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(command.getValue().periodEnd()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(command.getValue().dueDate()).isEqualTo(LocalDate.parse("2026-07-27"));
    }

    @Test
    void skipsWhenTodayDoesNotFollowCloseDate() {
        StatementBatchResult result = service.runDueBatch(LocalDate.parse("2026-06-30"));

        assertThat(result.due()).isFalse();
        verify(batchRepository, never())
                .findCreditAccountIdsWithUnbilledPostedTransactions(any(), any(), anyInt());
        verify(statementService, never()).generate(any());
    }

    @Test
    void movesDueDateToNextBusinessDayWhenBaseDayIsWeekend() {
        when(batchRepository.findCreditAccountIdsWithUnbilledPostedTransactions(
                eq(Instant.parse("2026-08-01T00:00:00Z")),
                eq(Instant.parse("2026-09-01T00:00:00Z")),
                eq(100)
        )).thenReturn(List.of(ACCOUNT_ID));

        StatementBatchResult result = service.runDueBatch(LocalDate.parse("2026-09-01"));

        assertThat(result.periodStart()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(result.periodEnd()).isEqualTo(LocalDate.parse("2026-08-31"));
        assertThat(result.dueDate()).isEqualTo(LocalDate.parse("2026-09-28"));
    }
}
