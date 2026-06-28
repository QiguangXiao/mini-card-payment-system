package com.minicard.risk.infrastructure.mybatis;

import java.time.Instant;

import com.minicard.risk.application.CardRiskFeature;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 风控特征投影 MyBatis mapper。
 *
 * <p>关键词：风控投影, 授权决策特征, upsert, risk feature projection,
 * authorization decision, projection table, リスク特徴量(リスクとくちょうりょう),
 * 投影(とうえい)。</p>
 */
@Mapper
public interface CardRiskFeatureProjectionMapper {

    /**
     * 把 authorization 决策结果 upsert 到风险特征表。
     */
    int upsertDecision(
            @Param("cardId") String cardId,
            @Param("status") String status,
            @Param("decidedAt") Instant decidedAt
    );

    /**
     * 读取一张卡的长期风险画像；没有历史事件时返回 null，由 adapter 转成 Optional.empty()。
     */
    CardRiskFeature findByCardId(@Param("cardId") String cardId);
}
