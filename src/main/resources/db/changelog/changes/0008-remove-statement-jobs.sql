--liquibase formatted sql

--changeset mini-card:0008-remove-statement-jobs dbms:mysql splitStatements:true
--comment: Drop statement_jobs after simplifying billing into a single daily batch loop.

-- 背景：出账原本是「durable claimable job + CRC32 按账户分片 + claim/lease/recover」的分片批处理，
-- 与 messaging/outbox、delayjob 构成第三套同构的 claim 循环。本项目是学习/interview用的最小实现，
-- 第三套同构实现的边际价值接近零，因此收敛成 StatementBatchService 的单线程逐账户循环。
--
-- 为什么删表是安全的：statement_jobs 只承载「本期出账任务的执行状态」，不是业务事实。
-- 业务事实分别在 statements（账单本身）和 card_transactions.billing_status（交易是否已出账）里。
-- 现在「本期还有谁没出账」直接由 card_transactions 的 POSTED + UNBILLED 查询回答——
-- 这个查询本身就是 level-triggered 的 desired state：出账后交易变 BILLED，自然从候选集合消失。
--
-- 反向事实：如果保留这张表但不再有写入方，它会变成一张永远停留在旧数据的僵尸表，
-- 让后来读代码的人以为出账仍然是分片任务驱动的。
--
-- 设计记录（分片、租约、recover 的完整推理）保留在 docs/claimable-jobs-cn.md；
-- 同一套 claim-lease-recover 模式在 outbox_events 和 delay_jobs 上仍有完整实现可讲。
DROP TABLE IF EXISTS statement_jobs;
