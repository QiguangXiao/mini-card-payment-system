package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDecision;
import com.minicard.authorization.domain.AuthorizationDecisionPolicy;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.authorization.domain.Money;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.domain.CardStatus;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

    private AuthorizationRepository authorizationRepository;
    private AuthorizationDecisionPolicy decisionPolicy;
    private CardRepository cardRepository;
    private CreditAccountRepository creditAccountRepository;
    private RiskAssessmentService riskAssessmentService;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        authorizationRepository = mock(AuthorizationRepository.class);
        decisionPolicy = mock(AuthorizationDecisionPolicy.class);
        cardRepository = mock(CardRepository.class);
        creditAccountRepository = mock(CreditAccountRepository.class);
        riskAssessmentService = mock(RiskAssessmentService.class);
        when(riskAssessmentService.assess(any())).thenReturn(RiskDecision.approve(20));
        service = new AuthorizationService(
                authorizationRepository,
                decisionPolicy,
                cardRepository,
                creditAccountRepository,
                riskAssessmentService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void approvesAndReservesAvailableCredit() {
        arrangeNewClaim();
        CreditAccount account = activeAccount("1000.00", "0.00");
        Card card = activeCard("card-123", account.id());
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(cardRepository.findById("card-123")).thenReturn(Optional.of(card));
        when(creditAccountRepository.findByIdForUpdate(account.id()))
                .thenReturn(Optional.of(account));

        Authorization result = service.authorize(command("key-1", "card-123", "100.00"));

        assertThat(result.status()).isEqualTo(AuthorizationStatus.APPROVED);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("100.00");
        verify(creditAccountRepository).update(account);
        verify(authorizationRepository).update(result);
    }

    @Test
    void declinesWithoutReservingWhenPolicyRejectsRequest() {
        arrangeNewClaim();
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.decline(
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED
        ));

        Authorization result = service.authorize(command("key-1", "card-123", "200000.00"));

        assertThat(result.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(result.declineReason())
                .contains(AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED);
        verify(cardRepository, never()).findById(any());
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
        verify(authorizationRepository).update(result);
    }

    @Test
    void declinesWhenAvailableCreditIsInsufficient() {
        arrangeNewClaim();
        CreditAccount account = activeAccount("1000.00", "950.00");
        Card card = activeCard("card-123", account.id());
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(cardRepository.findById("card-123")).thenReturn(Optional.of(card));
        when(creditAccountRepository.findByIdForUpdate(account.id()))
                .thenReturn(Optional.of(account));

        Authorization result = service.authorize(command("key-1", "card-123", "100.00"));

        assertThat(result.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(result.declineReason())
                .contains(AuthorizationDeclineReason.INSUFFICIENT_AVAILABLE_CREDIT);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("950.00");
        verify(creditAccountRepository, never()).update(any());
    }

    @Test
    void declinesWhenCardDoesNotExist() {
        arrangeNewClaim();
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(cardRepository.findById("unknown-card")).thenReturn(Optional.empty());

        Authorization result = service.authorize(command("key-1", "unknown-card", "100.00"));

        assertThat(result.declineReason())
                .contains(AuthorizationDeclineReason.CARD_NOT_FOUND);
    }

    @Test
    void declinesWhenCardIsBlocked() {
        arrangeNewClaim();
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(cardRepository.findById("card-blocked")).thenReturn(Optional.of(new Card(
                "card-blocked",
                UUID.randomUUID(),
                CardStatus.BLOCKED
        )));

        Authorization result = service.authorize(command("key-1", "card-blocked", "100.00"));

        assertThat(result.declineReason()).contains(AuthorizationDeclineReason.CARD_BLOCKED);
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void declinesWhenCardIsExpired() {
        arrangeNewClaim();
        when(decisionPolicy.decide(any())).thenReturn(AuthorizationDecision.approve());
        when(cardRepository.findById("card-expired")).thenReturn(Optional.of(new Card(
                "card-expired",
                UUID.randomUUID(),
                CardStatus.EXPIRED
        )));

        Authorization result = service.authorize(command("key-1", "card-expired", "100.00"));

        assertThat(result.declineReason()).contains(AuthorizationDeclineReason.CARD_EXPIRED);
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void returnsExistingIdempotentResultWithoutReservingAgain() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(authorizationRepository.claim(eq("key-1"), any())).thenReturn(false);
        when(authorizationRepository.findByIdempotencyKeyForUpdate("key-1"))
                .thenReturn(Optional.of(existing));

        Authorization result = service.authorize(command("key-1", "card-123", "100.0"));

        assertThat(result).isSameAs(existing);
        verify(decisionPolicy, never()).decide(any());
        verify(cardRepository, never()).findById(any());
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
        verify(authorizationRepository, never()).update(any());
    }

    @Test
    void rejectsDifferentRequestUsingSameIdempotencyKey() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(authorizationRepository.claim(eq("key-1"), any())).thenReturn(false);
        when(authorizationRepository.findByIdempotencyKeyForUpdate("key-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.authorize(command("key-1", "card-123", "200.00")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void getsAuthorizationById() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(authorizationRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        assertThat(service.get(existing.id())).isSameAs(existing);
    }

    @Test
    void throwsWhenAuthorizationDoesNotExist() {
        UUID id = UUID.fromString("8f2d8907-0471-4209-9862-73e09f62cd1f");
        when(authorizationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(AuthorizationNotFoundException.class);
    }

    private void arrangeNewClaim() {
        AtomicReference<Authorization> claimed = new AtomicReference<>();
        when(authorizationRepository.claim(eq("key-1"), any())).thenAnswer(invocation -> {
            claimed.set(invocation.getArgument(1));
            return true;
        });
        when(authorizationRepository.findByIdempotencyKeyForUpdate("key-1"))
                .thenAnswer(invocation -> Optional.of(claimed.get()));
    }

    private AuthorizationCommand command(String key, String cardId, String amount) {
        return new AuthorizationCommand(
                key,
                cardId,
                new BigDecimal(amount),
                Currency.getInstance("JPY"),
                "merchant-123",
                "JP",
                "JP"
        );
    }

    private CreditAccount activeAccount(String limit, String reserved) {
        Currency currency = Currency.getInstance("JPY");
        return CreditAccount.restore(
                UUID.randomUUID(),
                new Money(new BigDecimal(limit), currency),
                new Money(new BigDecimal(reserved), currency),
                CreditAccountStatus.ACTIVE
        );
    }

    private Card activeCard(String cardId, UUID accountId) {
        return new Card(cardId, accountId, CardStatus.ACTIVE);
    }

    private Authorization approvedAuthorization(String cardId, String amount) {
        return Authorization.restore(
                UUID.randomUUID(),
                command("existing-key", cardId, amount).requestFingerprint(),
                cardId,
                new Money(new BigDecimal(amount), Currency.getInstance("JPY")),
                AuthorizationStatus.APPROVED,
                null,
                NOW,
                NOW
        );
    }
}
