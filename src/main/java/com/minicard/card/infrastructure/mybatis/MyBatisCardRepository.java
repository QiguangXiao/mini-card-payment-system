package com.minicard.card.infrastructure.mybatis;

import java.util.Optional;
import java.util.UUID;

import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.domain.CardStatus;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCardRepository implements CardRepository {

    private final CardMapper cardMapper;

    public MyBatisCardRepository(CardMapper cardMapper) {
        this.cardMapper = cardMapper;
    }

    @Override
    public Optional<Card> findById(String cardId) {
        return Optional.ofNullable(cardMapper.findById(cardId))
                .map(row -> new Card(
                        row.id(),
                        UUID.fromString(row.creditAccountId()),
                        CardStatus.valueOf(row.status())
                ));
    }
}
