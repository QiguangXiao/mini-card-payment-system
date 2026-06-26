package com.minicard.authorization.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationPostedDomainEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-07T00:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-06-07T00:00:01Z");

    @Test
    void newAuthorizationStartsPending() {
        Authorization authorization = authorization();

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.PENDING);
        assertThat(authorization.declineReason()).isEmpty();
        assertThat(authorization.decidedAt()).isEmpty();
    }

    @Test
    void approvesPendingAuthorization() {
        Authorization authorization = authorization();

        authorization.approve(DECIDED_AT);

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.APPROVED);
        assertThat(authorization.declineReason()).isEmpty();
        assertThat(authorization.decidedAt()).contains(DECIDED_AT);
        assertThat(authorization.expiresAt()).contains(DECIDED_AT.plusSeconds(7 * 24 * 60 * 60));
        assertThat(authorization.expiredAt()).isEmpty();
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationApprovedDomainEvent.class);
        assertThat(authorization.pullDomainEvents()).isEmpty();
    }

    @Test
    void expiresApprovedAuthorizationAtOrAfterDeadline() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);
        authorization.pullDomainEvents();
        Instant expiryTime = DECIDED_AT.plusSeconds(7 * 24 * 60 * 60);

        authorization.expire(expiryTime);

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.EXPIRED);
        assertThat(authorization.expiredAt()).contains(expiryTime);
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationExpiredDomainEvent.class);
    }

    @Test
    void postsApprovedAuthorizationBeforeDeadline() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);
        authorization.pullDomainEvents();
        Instant postedAt = DECIDED_AT.plusSeconds(60);

        authorization.post(postedAt);

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.POSTED);
        assertThat(authorization.postedAt()).contains(postedAt);
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationPostedDomainEvent.class);
    }

    @Test
    void rejectsExpiryBeforeDeadline() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);

        assertThatThrownBy(() -> authorization.expire(DECIDED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authorization cannot expire before expiresAt");
    }

    @Test
    void declinesPendingAuthorizationWithReason() {
        Authorization authorization = authorization();

        authorization.decline(
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED,
                DECIDED_AT
        );

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(authorization.declineReason())
                .contains(AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED);
        assertThat(authorization.decidedAt()).contains(DECIDED_AT);
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationDeclinedDomainEvent.class);
    }

    @Test
    void rejectsSecondDecision() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);

        assertThatThrownBy(() -> authorization.decline(
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED,
                DECIDED_AT
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot decline authorization in status APPROVED");
    }

    @Test
    void rejectsZeroAuthorizationAmount() {
        assertThatThrownBy(() -> Authorization.request(
                "fingerprint-1",
                "card-123",
                new Money(BigDecimal.ZERO, Currency.getInstance("JPY")),
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authorization amount must be greater than zero");
    }

    private Authorization authorization() {
        return Authorization.request(
                "fingerprint-1",
                "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                CREATED_AT
        );
    }
}
