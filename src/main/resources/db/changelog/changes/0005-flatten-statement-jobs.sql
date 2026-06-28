--liquibase formatted sql

-- 0005 把 statement billing 从 “parent batch + sharded jobs” 两层结构扁平化成
-- 单层 sharded claimable jobs：cycle 身份(period/dueDate)直接落在 statement_jobs 上，
-- 删除 statement_batches。 “本期是否全部出账完成” 改成对 statement_jobs 的查询。
-- 采用 append-only changeset（不改 0001/0004），避免已应用 changeset 的 checksum 失效。

--changeset mini-card:0005-statement-jobs-add-cycle dbms:mysql splitStatements:true
--comment: Carry billing cycle (period/due) on the statement job itself.
-- statement_jobs 是 ephemeral 工作项，没有需要保留的业务数据；flatten 前清空在途行，
-- 避免新增 NOT NULL cycle 列时无法回填。
DELETE FROM statement_jobs;
ALTER TABLE statement_jobs
    ADD COLUMN period_start DATE NOT NULL AFTER id,
    ADD COLUMN period_end DATE NOT NULL AFTER period_start,
    ADD COLUMN due_date DATE NOT NULL AFTER period_end;

--changeset mini-card:0005-statement-jobs-drop-batch-link dbms:mysql splitStatements:true
--comment: Remove the parent-batch foreign key, unique and column.
-- 先删引用 batch_id 的 FK 和唯一键，才能安全删除 batch_id 列。
ALTER TABLE statement_jobs DROP FOREIGN KEY fk_statement_jobs_batch;
ALTER TABLE statement_jobs DROP INDEX uk_statement_jobs_batch_shard;
ALTER TABLE statement_jobs DROP COLUMN batch_id;

--changeset mini-card:0005-statement-jobs-cycle-constraints dbms:mysql splitStatements:true
--comment: Cycle/shard uniqueness gives idempotent job creation without a batch row.
-- (period_start, period_end, shard_no) 唯一键让 scheduler 重复触发同一周期保持幂等，
-- 取代原来 batch 唯一键提供的 creation idempotency。
ALTER TABLE statement_jobs
    ADD CONSTRAINT uk_statement_jobs_cycle_shard UNIQUE (period_start, period_end, shard_no);
ALTER TABLE statement_jobs
    ADD CONSTRAINT chk_statement_jobs_period CHECK (period_end >= period_start AND due_date > period_end);

--changeset mini-card:0005-drop-statement-batches dbms:mysql
--comment: Parent batch lifecycle removed; cycle completion is now a query over statement_jobs.
DROP TABLE IF EXISTS statement_batches;
