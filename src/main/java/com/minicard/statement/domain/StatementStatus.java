package com.minicard.statement.domain;

public enum StatementStatus {
    /**
     * Billing cycle 已关闭，账单金额已经固定，等待后续 Payment 阶段处理。
     */
    CLOSED,
    /**
     * 未来还款阶段使用：已还一部分，但还没有完全结清。
     */
    PARTIALLY_PAID,
    /**
     * 未来还款阶段使用：账单已完全结清。
     */
    PAID,
    /**
     * 未来 due-date scheduler 使用：到期后仍未还清。
     */
    OVERDUE
}
