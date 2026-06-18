package com.minicard.authorization.infrastructure.delayjob;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.Money;
import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobRepository;
import com.minicard.delayjob.DelayJobType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthorizationExpiryDelayJobSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void schedulesAuthorizationExpiryAtTheBusinessDeadline() {
        DelayJobRepository repository = mock(DelayJobRepository.class);
        AuthorizationExpiryDelayJobScheduler scheduler =
                new AuthorizationExpiryDelayJobScheduler(
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
