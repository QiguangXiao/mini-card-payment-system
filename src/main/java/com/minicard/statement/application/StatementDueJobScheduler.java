package com.minicard.statement.application;

import com.minicard.statement.domain.Statement;

/**
 * Statement close 后要计划的 due-date business action。
 *
 * <p>StatementService 只表达“账单到期日需要后续动作”这个业务意图；
 * 具体用 DelayJob 还是别的调度机制，由 adapter 决定。</p>
 */
public interface StatementDueJobScheduler {

    void scheduleAutoRepayment(Statement statement);
}
