package com.minicard.card.infrastructure.mybatis;

/**
 * cards 表的 MyBatis row DTO。
 *
 * <p>关键词：卡片行, 持久化模型, 额度账户关联, card row,
 * persistence row, credit account link, カード行(カードぎょう),
 * 永続化(えいぞくか)。</p>
 */
public record CardRow(
        /** card business id。 */
        String id,
        /** 所属 credit account id。 */
        String creditAccountId,
        /** CardStatus 字符串。 */
        String status
) {
}
