package com.minicard.risk.infrastructure.mybatis;

import java.util.Optional;

import com.minicard.risk.application.CardRiskFeature;
import com.minicard.risk.application.RiskFeatureReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 风控长期画像读取 adapter。
 *
 * <p>关键词：风控画像读取, MyBatis adapter, CQRS read model,
 * risk feature reader, リスク特徴量(リスクとくちょうりょう)。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisRiskFeatureReader implements RiskFeatureReader {

    private final CardRiskFeatureProjectionMapper mapper;

    @Override
    public Optional<CardRiskFeature> findByCardId(String cardId) {
        return Optional.ofNullable(mapper.findByCardId(cardId));
    }
}
