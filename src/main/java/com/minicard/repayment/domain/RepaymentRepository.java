package com.minicard.repayment.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Repayment aggregate 的 repository port。
 *
 * <p>还款 API 是 state-changing operation，必须用 idempotency key 抢占请求所有权。
 * duplicate retry 通过 findByIdempotencyKeyForUpdate 等待 winner 完成。</p>
 */
public interface RepaymentRepository {

    boolean claim(Repayment pendingRepayment);

    Optional<Repayment> findByIdempotencyKeyForUpdate(String idempotencyKey);

    Optional<Repayment> findById(UUID id);

    void update(Repayment repayment);
}
