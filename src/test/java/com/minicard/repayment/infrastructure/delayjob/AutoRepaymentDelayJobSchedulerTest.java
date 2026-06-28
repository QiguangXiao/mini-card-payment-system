package com.minicard.repayment.infrastructure.delayjob;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobRepository;
import com.minicard.delayjob.DelayJobType;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementLineSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutoRepaymentDelayJobSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    void schedulesAutoRepaymentAtStatementDueDateStart() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        AutoRepaymentDelayJobScheduler scheduler = new AutoRepaymentDelayJobScheduler(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Statement statement = statement();

        scheduler.scheduleAutoRepayment(statement);

        ArgumentCaptor<DelayJob> job = ArgumentCaptor.forClass(DelayJob.class);
        verify(repository).insertIfAbsent(job.capture());
        assertThat(job.getValue().jobType()).isEqualTo(DelayJobType.AUTO_REPAYMENT);
        assertThat(job.getValue().aggregateType()).isEqualTo("Statement");
        assertThat(job.getValue().aggregateId()).isEqualTo(statement.id().toString());
        assertThat(job.getValue().scheduledAt())
                .isEqualTo(Instant.parse("2026-07-26T15:00:00Z"));
        assertThat(job.getValue().nextAttemptAt()).isEqualTo(job.getValue().scheduledAt());
    }

    private Statement statement() {
        Statement statement = Statement.close(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                LocalDate.parse("2026-05-16"),
                LocalDate.parse("2026-06-15"),
                LocalDate.parse("2026-07-27"),
                List.of(new StatementLineSource(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ntx-001",
                        UUID.randomUUID(),
                        "card-123",
                        money("1500.00"),
                        Instant.parse("2026-06-01T10:00:00Z")
                )),
                money("1000.00"),
                NOW
        );
        return statement;
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
