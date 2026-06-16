package com.minicard.card.domain;

import java.util.Optional;

/**
 * Card aggregate 的 repository port。
 *
 * <p>当前授权流程只读取卡状态，不在这里加锁；可变额度由 CreditAccountRepository 控制。</p>
 */
public interface CardRepository {

    Optional<Card> findById(String cardId);
}
