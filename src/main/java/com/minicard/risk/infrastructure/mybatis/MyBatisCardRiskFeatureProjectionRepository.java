package com.minicard.risk.infrastructure.mybatis;

import com.minicard.risk.application.projection.CardRiskFeatureProjectionRepository;
import com.minicard.risk.application.projection.RecordAuthorizationDecisionCommand;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCardRiskFeatureProjectionRepository
        implements CardRiskFeatureProjectionRepository {

    private final CardRiskFeatureProjectionMapper mapper;

    public MyBatisCardRiskFeatureProjectionRepository(CardRiskFeatureProjectionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void applyDecision(RecordAuthorizationDecisionCommand decision) {
        mapper.upsertDecision(decision);
    }
}
