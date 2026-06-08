package com.minicard.card.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {

    @Test
    void rejectsBlankCardId() {
        assertThatThrownBy(() -> new Card(" ", UUID.randomUUID(), CardStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("card id must not be blank");
    }

    @Test
    void activeCardIsEligibleForAuthorization() {
        Card card = new Card("card-123", UUID.randomUUID(), CardStatus.ACTIVE);

        assertThat(card.checkAuthorizationEligibility().eligible()).isTrue();
    }

    @Test
    void blockedCardIsNotEligibleForAuthorization() {
        Card card = new Card("card-123", UUID.randomUUID(), CardStatus.BLOCKED);

        assertThat(card.checkAuthorizationEligibility().optionalFailure())
                .contains(CardAuthorizationFailure.CARD_BLOCKED);
    }

    @Test
    void expiredCardIsNotEligibleForAuthorization() {
        Card card = new Card("card-123", UUID.randomUUID(), CardStatus.EXPIRED);

        assertThat(card.checkAuthorizationEligibility().optionalFailure())
                .contains(CardAuthorizationFailure.CARD_EXPIRED);
    }
}
