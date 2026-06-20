package com.minicard.repayment.domain;

public enum RepaymentStatus {
    /**
     * 幂等键已经被本事务 claim，正在准备锁账户和账单。
     */
    PENDING,
    /**
     * 还款已成功应用到账户 postedBalance 和 statement paidAmount。
     */
    RECEIVED
}
