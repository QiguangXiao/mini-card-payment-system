package com.minicard.card.application;

import java.util.UUID;

import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardStatus;

/**
 * Card 的缓存快照。
 *
 * <p>关键词：卡片快照, 卡状态, Card snapshot, reference data,
 * card lifecycle, カード状態(カードじょうたい),
 * 参照データ(さんしょうデータ)。</p>
 *
 * <p>Card 本身是很小的 immutable aggregate，但这里仍然单独建 snapshot，是为了把
 * Redis JSON contract 和 domain object 解耦：以后 Card aggregate 增加行为方法时，
 * cache 仍然只保存可重建的字段。</p>
 */
public record CardSnapshot(
        String id,
        UUID creditAccountId,
        CardStatus status
) {

    public CardSnapshot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("card snapshot id must not be blank");
        }
        if (creditAccountId == null) {
            throw new IllegalArgumentException("card snapshot creditAccountId must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("card snapshot status must not be null");
        }
    }

    public static CardSnapshot from(Card card) {
        return new CardSnapshot(card.id(), card.creditAccountId(), card.status());
    }

    public Card toCard() {
        return new Card(id, creditAccountId, status);
    }
}
