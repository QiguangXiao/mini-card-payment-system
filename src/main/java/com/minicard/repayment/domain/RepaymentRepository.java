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

    // claim 用 INSERT-first + unique key 占住 idempotency key。
    // 如果只提供 save/find，调用方很容易写出先查再插的竞态。
    boolean claim(Repayment pendingRepayment);

    // Optional 明确表达“可能还没找到 winner row”；比返回 null 更能逼调用方处理缺失分支。
    Optional<Repayment> findByIdempotencyKeyForUpdate(String idempotencyKey);

    Optional<Repayment> findById(UUID id);

    void update(Repayment repayment);
}
