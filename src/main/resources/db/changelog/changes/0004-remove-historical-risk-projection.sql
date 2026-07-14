--liquibase formatted sql

--changeset mini-card:0004-remove-historical-risk-projection dbms:mysql splitStatements:true
--comment: Remove the historical card risk projection; keep real-time velocity and external risk assessment.

-- 此 consumer 已不再存在，删除它的 Inbox 进度，避免运维查询误以为仍有 Risk Kafka 下游。
DELETE FROM consumer_inbox
WHERE consumer_name IN ('risk-feature-v1', 'risk-feature-projector');

-- 授权风控不再读取异步历史画像；实时 Redis velocity 和 external risk 是仅保留的风险信号。
DROP TABLE card_risk_features;
