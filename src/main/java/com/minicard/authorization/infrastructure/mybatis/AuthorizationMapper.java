package com.minicard.authorization.infrastructure.mybatis;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthorizationMapper {

    AuthorizationRow findById(@Param("id") String id);

    int insert(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("authorization") AuthorizationRow authorization
    );

    AuthorizationRow findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey
    );

    AuthorizationRow findNextExpiredApprovedForUpdate(@Param("now") Instant now);

    int update(AuthorizationRow authorization);
}
