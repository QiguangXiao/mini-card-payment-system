package com.minicard.risk.infrastructure.mybatis;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CardRiskFeatureProjectionMapper {

    int upsertDecision(
            @Param("cardId") String cardId,
            @Param("status") String status,
            @Param("decidedAt") Instant decidedAt
    );
}
