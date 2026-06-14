package com.minicard.risk.infrastructure.mybatis;

import com.minicard.risk.application.projection.RecordAuthorizationDecisionCommand;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CardRiskFeatureProjectionMapper {

    int upsertDecision(RecordAuthorizationDecisionCommand decision);
}
