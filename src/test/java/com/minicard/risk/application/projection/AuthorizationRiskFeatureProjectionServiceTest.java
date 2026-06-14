package com.minicard.risk.application.projection;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationRiskFeatureProjectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    void appliesProjectionOnlyAfterWinningInboxClaim() {
        ConsumerInboxRepository inbox = mock(ConsumerInboxRepository.class);
        CardRiskFeatureProjectionRepository projection =
                mock(CardRiskFeatureProjectionRepository.class);
        RecordAuthorizationDecisionCommand decision = declinedDecision();
        when(inbox.claim(
                eq(AuthorizationRiskFeatureProjectionService.CONSUMER_NAME),
                eq(decision.eventId()),
                any()
        )).thenReturn(true);

        service(inbox, projection).project(decision);

        verify(projection).applyDecision(decision);
    }

    @Test
    void duplicateInboxClaimDoesNotIncrementProjectionAgain() {
        ConsumerInboxRepository inbox = mock(ConsumerInboxRepository.class);
        CardRiskFeatureProjectionRepository projection =
                mock(CardRiskFeatureProjectionRepository.class);
        RecordAuthorizationDecisionCommand decision = declinedDecision();
        when(inbox.claim(
                eq(AuthorizationRiskFeatureProjectionService.CONSUMER_NAME),
                eq(decision.eventId()),
                any()
        )).thenReturn(false);

        service(inbox, projection).project(decision);

        verify(projection, never()).applyDecision(any());
    }

    private AuthorizationRiskFeatureProjectionService service(
            ConsumerInboxRepository inbox,
            CardRiskFeatureProjectionRepository projection
    ) {
        return new AuthorizationRiskFeatureProjectionService(
                inbox,
                projection,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private RecordAuthorizationDecisionCommand declinedDecision() {
        return new RecordAuthorizationDecisionCommand(
                UUID.randomUUID(),
                "card-123",
                "DECLINED",
                NOW
        );
    }
}
