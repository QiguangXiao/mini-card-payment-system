--liquibase formatted sql

--changeset mini-card:0008-drop-old-statement-job-claim-state-check dbms:mysql
--comment: Drop old claim-state check before adding explicit claim_token to statement_jobs.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_statement_jobs_claim_state'
ALTER TABLE statement_jobs DROP CHECK chk_statement_jobs_claim_state;

--changeset mini-card:0008-add-statement-job-claim-token dbms:mysql
--comment: Add explicit owner token for StatementJob PROCESSING lease. claim_until remains the timeout deadline; claim_token is the finalize ownership check.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'statement_jobs' AND column_name = 'claim_token'
ALTER TABLE statement_jobs ADD COLUMN claim_token CHAR(36) NULL AFTER claim_until;

--changeset mini-card:0008-backfill-statement-job-processing-claim-token dbms:mysql
--comment: Existing PROCESSING jobs must receive a token before the stricter claim-state check is restored.
UPDATE statement_jobs
SET claim_token = UUID()
WHERE status = 'PROCESSING'
  AND claim_token IS NULL;

--changeset mini-card:0008-add-new-statement-job-claim-state-check dbms:mysql
--comment: PROCESSING rows must carry complete claim metadata; non-PROCESSING rows must not retain stale claim ownership.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_statement_jobs_claim_state'
ALTER TABLE statement_jobs
    ADD CONSTRAINT chk_statement_jobs_claim_state CHECK (
        (status = 'PROCESSING'
            AND claimed_by IS NOT NULL
            AND claimed_at IS NOT NULL
            AND claim_until IS NOT NULL
            AND claim_token IS NOT NULL)
        OR (status <> 'PROCESSING'
            AND claimed_by IS NULL
            AND claimed_at IS NULL
            AND claim_until IS NULL
            AND claim_token IS NULL)
    );
