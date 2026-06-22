package com.minicard.card.infrastructure.mybatis;

import java.util.Optional;
import java.util.UUID;

import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.domain.CardStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * CardRepository 的 MyBatis adapter，负责把 cards 表记录还原成 Card domain object。
 *
 * <p>Card 查询不加 row lock，因为授权流程只读卡生命周期；真正会变化的额度在
 * CreditAccount 上加锁。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisCardRepository implements CardRepository {

    private final CardMapper cardMapper;

    @Override
    public Optional<Card> findById(String cardId) {
        // Card id 是外部请求入口字段；找不到卡时 authorization 仍会留下 DECLINED audit row。
        return Optional.ofNullable(cardMapper.findById(cardId))
                .map(row -> new Card(
                        row.id(),
                        // String -> UUID、String -> enum 的转换留在 adapter，domain 不暴露数据库列表示。
                        // 如果把 row 直接传到 service，业务层会开始依赖 persistence schema。
                        UUID.fromString(row.creditAccountId()),
                        CardStatus.valueOf(row.status())
                ));
    }
}
