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
// @Mapper 让 MyBatis/Spring 生成 mapper proxy。没有这个代理，Repository 无法注入接口实现。
// @Param 名称要和 XML 中的 #{...} 对齐；否则多个参数时 MyBatis 只能看到 param1/param2。
@Mapper
public interface AuthorizationMapper {

    /**
     * 按 id 普通查询 authorization。
     */
    AuthorizationRow findById(@Param("id") String id);

    /**
     * 插入 authorization，同时写入 idempotency key 唯一索引。
     */
    int insert(@Param("authorization") AuthorizationRow authorization);

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
