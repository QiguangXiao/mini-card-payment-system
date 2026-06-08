package com.minicard.card.domain;

import java.util.Optional;

public interface CardRepository {

    Optional<Card> findById(String cardId);
}
