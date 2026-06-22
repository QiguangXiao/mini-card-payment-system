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
    // 方法名显式带 ForUpdate，调用点能看见这里会拿 row lock。
    // 如果只叫 findById，review 时很难判断并发安全是否依赖这个查询。
    Optional<CreditAccount> findByIdForUpdate(UUID accountId);

    // update 接收 aggregate 而不是 row，避免 application 绕过 domain invariant 直接改金额列。
    void update(CreditAccount account);
}
