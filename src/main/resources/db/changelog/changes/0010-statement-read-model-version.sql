--liquibase formatted sql

--changeset mini-card:0010-statements-add-version dbms:mysql
--comment: Add an explicit monotonic statement read-model version for Redis L2 CAS/tombstone. Existing statements start at version 0 because their cached snapshots can be rebuilt from MySQL.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'statements' AND column_name = 'version'
ALTER TABLE statements ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status;

--changeset mini-card:0010-statements-version-check dbms:mysql
--comment: Statement versions must be monotonic non-negative counters; negative values would break cache CAS ordering.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_statements_version'
ALTER TABLE statements
    ADD CONSTRAINT chk_statements_version CHECK (version >= 0);
