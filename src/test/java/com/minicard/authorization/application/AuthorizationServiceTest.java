package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDecision;
import com.minicard.authorization.domain.AuthorizationDecisionPolicy;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.authorization.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

    private AuthorizationRepository repository;
    private AuthorizationDecisionPolicy decisionPolicy;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuthorizationRepository.class);
        decisionPolicy = mock(AuthorizationDecisionPolicy.class);
        service = new AuthorizationService(
                repository,
                decisionPolicy,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void approvesRequestWhenPolicyApproves() {
        AuthorizationCommand command = command("key-1", "card-123", "100.00");
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(repository.saveOrGet(eq("key-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        Authorization result = service.authorize(command);

        ArgumentCaptor<Authorization> captor = ArgumentCaptor.forClass(Authorization.class);
        verify(repository).saveOrGet(eq("key-1"), captor.capture());
        assertThat(result).isSameAs(captor.getValue());
        assertThat(result.status()).isEqualTo(AuthorizationStatus.APPROVED);
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.decidedAt()).contains(NOW);
    }

    @Test
    void declinesRequestWhenPolicyDeclines() {
        AuthorizationCommand command = command("key-1", "card-123", "200000.00");
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.decline(
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED
        ));
        when(repository.saveOrGet(eq("key-1"), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        Authorization result = service.authorize(command);

        assertThat(result.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(result.declineReason())
                .contains(AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED);
    }

    @Test
    void returnsExistingAuthorizationForSameIdempotentRequest() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(repository.saveOrGet(eq("key-1"), any())).thenReturn(existing);

        Authorization result = service.authorize(command("key-1", "card-123", "100.0"));

        assertThat(result).isSameAs(existing);
    }

    @Test
    void rejectsDifferentRequestUsingSameIdempotencyKey() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(repository.saveOrGet(eq("key-1"), any())).thenReturn(existing);

        assertThatThrownBy(() -> service.authorize(command("key-1", "card-123", "200.00")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void getsAuthorizationById() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(repository.findById(existing.id())).thenReturn(Optional.of(existing));

        assertThat(service.get(existing.id())).isSameAs(existing);
    }

    @Test
    void throwsWhenAuthorizationDoesNotExist() {
        UUID id = UUID.fromString("8f2d8907-0471-4209-9862-73e09f62cd1f");
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(AuthorizationNotFoundException.class);
    }

    private AuthorizationCommand command(String key, String cardId, String amount) {
        return new AuthorizationCommand(
                key,
                cardId,
                new BigDecimal(amount),
                Currency.getInstance("JPY")
        );
    }

    private Authorization approvedAuthorization(String cardId, String amount) {
        return Authorization.restore(
                UUID.randomUUID(),
                cardId,
                new Money(new BigDecimal(amount), Currency.getInstance("JPY")),
                AuthorizationStatus.APPROVED,
                null,
                NOW,
                NOW
        );
    }
}
