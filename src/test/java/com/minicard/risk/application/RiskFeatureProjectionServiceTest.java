package com.minicard.risk.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import com.minicard.risk.infrastructure.mybatis.CardRiskFeatureProjectionMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskFeatureProjectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void appliesProjectionOnlyAfterWinningInboxClaim() {
        ConsumerInboxRepository inbox = mock(ConsumerInboxRepository.class);
        CardRiskFeatureProjectionMapper mapper = mock(CardRiskFeatureProjectionMapper.class);
        UUID eventId = UUID.randomUUID();
        when(inbox.claim(eq(RiskFeatureProjectionService.CONSUMER_NAME), eq(eventId), any()))
                .thenReturn(true);

        service(inbox, mapper).project(eventId, "card-123", "DECLINED", NOW);

        verify(mapper).upsertDecision("card-123", "DECLINED", NOW);
    }

    @Test
    void duplicateInboxClaimDoesNotIncrementProjectionAgain() {
        ConsumerInboxRepository inbox = mock(ConsumerInboxRepository.class);
        CardRiskFeatureProjectionMapper mapper = mock(CardRiskFeatureProjectionMapper.class);
        UUID eventId = UUID.randomUUID();
        when(inbox.claim(eq(RiskFeatureProjectionService.CONSUMER_NAME), eq(eventId), any()))
                .thenReturn(false);

        service(inbox, mapper).project(eventId, "card-123", "DECLINED", NOW);

        verify(mapper, never()).upsertDecision(any(), any(), any());
    }

    private RiskFeatureProjectionService service(
            ConsumerInboxRepository inbox,
            CardRiskFeatureProjectionMapper mapper
    ) {
        return new RiskFeatureProjectionService(
                inbox,
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
