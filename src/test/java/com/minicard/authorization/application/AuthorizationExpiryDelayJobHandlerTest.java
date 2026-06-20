package com.minicard.authorization.application;

import java.time.Instant;
import java.util.UUID;

import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthorizationExpiryDelayJobHandlerTest {

    @Test
    void dispatchesAuthorizationExpiryJobToService() {
        AuthorizationExpiryService service = mock(AuthorizationExpiryService.class);
        AuthorizationExpiryDelayJobHandler handler = new AuthorizationExpiryDelayJobHandler(service);
        UUID authorizationId = UUID.randomUUID();

        handler.handle(job("Authorization", authorizationId));

        verify(service).expire(authorizationId);
    }

    @Test
    void rejectsJobForUnexpectedAggregateType() {
        AuthorizationExpiryService service = mock(AuthorizationExpiryService.class);
        AuthorizationExpiryDelayJobHandler handler = new AuthorizationExpiryDelayJobHandler(service);

        assertThatThrownBy(() -> handler.handle(job("Statement", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AUTHORIZATION_EXPIRY job must target Authorization aggregate");
    }

    private DelayJob job(String aggregateType, UUID aggregateId) {
        Instant now = Instant.parse("2026-06-08T00:00:00Z");
        return DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTHORIZATION_EXPIRY,
                aggregateType,
                aggregateId.toString(),
                now,
                now
        );
    }
}
