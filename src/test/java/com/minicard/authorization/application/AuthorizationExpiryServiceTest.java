package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.authorization.domain.Money;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.domain.CardStatus;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationExpiryServiceTest {

    private static final Instant APPROVED_AT = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void expiresAuthorizationAndReleasesCreditInOneUseCase() {
        AuthorizationRepository authorizationRepository = mock(AuthorizationRepository.class);
        CardRepository cardRepository = mock(CardRepository.class);
        CreditAccountRepository accountRepository = mock(CreditAccountRepository.class);
        AuthorizationExpiryEventPublisher eventPublisher =
                mock(AuthorizationExpiryEventPublisher.class);
        Authorization authorization = approvedAuthorization();
        CreditAccount account = account();
        Card card = new Card("card-123", account.id(), CardStatus.ACTIVE);
        when(authorizationRepository.findNextExpiredApprovedForUpdate(NOW))
                .thenReturn(Optional.of(authorization));
        when(cardRepository.findById("card-123")).thenReturn(Optional.of(card));
        when(accountRepository.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));
        AuthorizationExpiryService service = new AuthorizationExpiryService(
                authorizationRepository,
                cardRepository,
                accountRepository,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        boolean processed = service.expireNext();

        assertThat(processed).isTrue();
        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.EXPIRED);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("200.00");
        // This order documents the transaction's financial consistency story:
        // release the locked balance, persist both aggregates, then append the
        // event intent before the surrounding transaction commits.
        var order = inOrder(accountRepository, authorizationRepository, eventPublisher);
        order.verify(accountRepository).update(account);
        order.verify(authorizationRepository).update(authorization);
        order.verify(eventPublisher).append(authorization);
    }

    @Test
    void returnsWithoutSideEffectsWhenNoAuthorizationIsDue() {
        AuthorizationRepository authorizationRepository = mock(AuthorizationRepository.class);
        CardRepository cardRepository = mock(CardRepository.class);
        CreditAccountRepository accountRepository = mock(CreditAccountRepository.class);
        AuthorizationExpiryEventPublisher eventPublisher =
                mock(AuthorizationExpiryEventPublisher.class);
        when(authorizationRepository.findNextExpiredApprovedForUpdate(NOW))
                .thenReturn(Optional.empty());
        AuthorizationExpiryService service = new AuthorizationExpiryService(
                authorizationRepository,
                cardRepository,
                accountRepository,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(service.expireNext()).isFalse();

        verify(cardRepository, never()).findById(org.mockito.ArgumentMatchers.any());
        verify(accountRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
        verify(eventPublisher, never()).append(org.mockito.ArgumentMatchers.any());
    }

    private Authorization approvedAuthorization() {
        Authorization authorization = Authorization.request(
                "fingerprint",
                "card-123",
                money("100.00"),
                APPROVED_AT
        );
        authorization.approve(APPROVED_AT);
        return authorization;
    }

    private CreditAccount account() {
        return CreditAccount.restore(
                UUID.randomUUID(),
                money("1000.00"),
                money("300.00"),
                CreditAccountStatus.ACTIVE
        );
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("JPY"));
    }
}
