--liquibase formatted sql

--changeset mini-card:0003-remove-ledger-projection dbms:mysql splitStatements:true
--comment: Remove the optional Ledger projection and let statements snapshot posted card transactions directly.

-- 先解除 statement_lines 对 ledger_entries 的外键和唯一索引，再删列/表。
-- 反向事实：如果先 DROP ledger_entries，MySQL 会因仍有外键引用而拒绝 migration。
ALTER TABLE statement_lines
    DROP FOREIGN KEY fk_statement_lines_ledger_entry,
    DROP INDEX uk_statement_lines_ledger_entry,
    DROP COLUMN ledger_entry_id;

DROP TABLE ledger_entries;
