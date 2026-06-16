package com.minicard.authorization.infrastructure.scheduling;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.Money;
import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobRepository;
import com.minicard.scheduling.domain.DelayJobType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DelayJobAuthorizationExpiryJobSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void schedulesAuthorizationExpiryAtTheBusinessDeadline() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        DelayJobAuthorizationExpiryJobScheduler scheduler =
                new DelayJobAuthorizationExpiryJobScheduler(
                        repository,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );
        Authorization authorization = Authorization.request(
                "fingerprint",
                "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                NOW
        );
        authorization.approve(NOW);

        scheduler.schedule(authorization);

        ArgumentCaptor<DelayJob> job = ArgumentCaptor.forClass(DelayJob.class);
        verify(repository).insertIfAbsent(job.capture());
        assertThat(job.getValue().jobType()).isEqualTo(DelayJobType.AUTHORIZATION_EXPIRY);
        assertThat(job.getValue().aggregateType()).isEqualTo("Authorization");
        assertThat(job.getValue().aggregateId()).isEqualTo(authorization.id().toString());
        assertThat(job.getValue().scheduledAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));
        assertThat(job.getValue().nextAttemptAt()).isEqualTo(job.getValue().scheduledAt());
    }
}
