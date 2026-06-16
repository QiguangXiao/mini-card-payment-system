package com.minicard.authorization.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Authorization aggregate 的 repository port。
 *
 * <p>Domain/application 只依赖这个接口，不依赖 MyBatis。带 ForUpdate 的方法明确表示
 * 调用方需要数据库 row lock，这是金融并发控制的关键语义。</p>
 */
public interface AuthorizationRepository {

    Optional<Authorization> findById(UUID id);

    /**
     * 用 idempotencyKey 唯一索引抢占请求所有权。
     *
     * @return true 表示当前请求是 winner；false 表示重复请求，应读取已有结果
     */
    boolean claim(String idempotencyKey, Authorization pendingAuthorization);

    /**
     * 锁定同一个幂等键对应的 authorization row，等待 winner 完成最终状态。
     */
    Optional<Authorization> findByIdempotencyKeyForUpdate(String idempotencyKey);

    Optional<Authorization> findByIdForUpdate(UUID id);

    void update(Authorization authorization);
}
