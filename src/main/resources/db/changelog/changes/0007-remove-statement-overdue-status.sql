--liquibase formatted sql

--changeset mini-card:0007-remove-statement-overdue-status dbms:mysql splitStatements:true
--comment: Tighten statement status checks after deleting the never-written OVERDUE state.

-- OVERDUE 从未有写入方（Java 侧删除前 StatementStatus.OVERDUE 没有任何赋值路径），
-- 所以这里不需要 UPDATE/DELETE 数据迁移，只收紧约束让 DB 和 StatementStatus 枚举一致。
-- 反向事实：如果只删 Java enum 而保留宽约束，手工修数据或未来 bug 写入 'OVERDUE' 后，
-- MyBatis 恢复 Statement 时 StatementStatus.valueOf 会抛异常，账单读路径直接 500；
-- 约束收紧后这类脏写在 INSERT/UPDATE 时就被 DB 拒绝，错误停在写入方。
ALTER TABLE statements
    DROP CHECK chk_statements_status;

ALTER TABLE statements
    ADD CONSTRAINT chk_statements_status CHECK (
        status IN ('CLOSED', 'PARTIALLY_PAID', 'PAID')
    );

ALTER TABLE statements
    DROP CHECK chk_statements_payment_state;

-- 与 Statement.validatePaymentState() 保持同一张对照表：
-- CLOSED = 尚未还款；PARTIALLY_PAID = 已还一部分；PAID = 全额结清。
ALTER TABLE statements
    ADD CONSTRAINT chk_statements_payment_state CHECK (
        (status = 'CLOSED' AND paid_amount = 0)
        OR (status = 'PARTIALLY_PAID' AND paid_amount > 0 AND paid_amount < total_amount)
        OR (status = 'PAID' AND paid_amount = total_amount)
    );
