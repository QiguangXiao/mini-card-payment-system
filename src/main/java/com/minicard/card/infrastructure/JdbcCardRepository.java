package com.minicard.card.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.domain.CardStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCardRepository implements CardRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Card> findById(String cardId) {
        return jdbcTemplate.query(
                "SELECT id, credit_account_id, status FROM cards WHERE id = ?",
                (resultSet, rowNum) -> new Card(
                        resultSet.getString("id"),
                        UUID.fromString(resultSet.getString("credit_account_id")),
                        CardStatus.valueOf(resultSet.getString("status"))
                ),
                cardId
        ).stream().findFirst();
    }
}
