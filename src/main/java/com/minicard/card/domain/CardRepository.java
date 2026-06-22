package com.minicard.card.domain;

import java.util.Optional;

/**
 * Card aggregate 的 repository port。
 *
 * <p>当前授权流程只读取卡状态，不在这里加锁；可变额度由 CreditAccountRepository 控制。</p>
 */
public interface CardRepository {

    // Optional 表达 card 可能不存在；如果返回 null，调用方容易漏判后触发 NPE。
    // 当前不加 FOR UPDATE，因为授权真正修改的是 CreditAccount reservation。
    Optional<Card> findById(String cardId);
}
