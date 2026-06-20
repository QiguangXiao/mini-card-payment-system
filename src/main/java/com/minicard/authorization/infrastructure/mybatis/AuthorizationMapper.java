package com.minicard.authorization.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Authorization MyBatis mapper。
 *
 * <p>关键词：授权 SQL, 幂等键, 行锁, authorization mapper,
 * idempotency key, row lock, オーソリSQL,
 * 行ロック(ぎょうロック)。</p>
 */
@Mapper
public interface AuthorizationMapper {

    /**
     * 按 id 普通查询 authorization。
     */
    AuthorizationRow findById(@Param("id") String id);

    /**
     * 插入 authorization，同时写入 idempotency key 唯一索引。
     */
    int insert(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("authorization") AuthorizationRow authorization
    );

    /**
     * 按 idempotency key 加 FOR UPDATE，等待首个请求完成后返回同一结果。
     */
    AuthorizationRow findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 按 authorization id 加 FOR UPDATE，posting/expiry 时防止并发状态覆盖。
     */
    AuthorizationRow findByIdForUpdate(@Param("id") String id);

    /**
     * 更新 authorization 状态和时间字段。
     */
    int update(AuthorizationRow authorization);
}
