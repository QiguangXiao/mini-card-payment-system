package com.minicard.risk.application;

import java.util.Optional;

/**
 * 风控长期画像读取 port。
 *
 * <p>关键词：风控画像, CQRS read model, projection read,
 * long-window risk feature, リスク特徴量(リスクとくちょうりょう)。</p>
 */
public interface RiskFeatureReader {

    /**
     * 按 cardId 读取 Kafka 异步维护的长期风险画像。
     */
    Optional<CardRiskFeature> findByCardId(String cardId);
}
