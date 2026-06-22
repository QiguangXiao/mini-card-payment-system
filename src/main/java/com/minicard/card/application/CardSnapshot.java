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

    // snapshot record 的 compact constructor 会保护 Redis 反序列化、测试 fixture 和手工构造路径。
    // 如果只在 repository 写入前校验，缓存里的坏 JSON 仍可能恢复成非法 Card。
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
        // from/toCard 把 cache JSON contract 和 domain aggregate 的转换集中在这里。
        // 如果各 repository 自己拼字段，Card 字段变化时更容易漏同步。
        return new CardSnapshot(card.id(), card.creditAccountId(), card.status());
    }

    public Card toCard() {
        return new Card(id, creditAccountId, status);
    }
}
