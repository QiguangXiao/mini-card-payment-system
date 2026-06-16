package com.minicard.creditaccount.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * CreditAccount aggregate 的 repository port。
 *
 * <p>额度变更必须先通过 findByIdForUpdate 拿 row lock，再调用 aggregate reserve/release。
 * 这是防止高并发 overspending 的核心接口约定。</p>
 */
public interface CreditAccountRepository {

    /**
     * 读取并锁定账户行，直到当前 transaction commit/rollback。
     */
    Optional<CreditAccount> findByIdForUpdate(UUID accountId);

    void update(CreditAccount account);
}
