package com.minicard.statement.application;

import com.minicard.statement.domain.Statement;

/**
 * Statement close 后要计划的 due-date business action。
 *
 * <p>关键词：到期任务, 自动扣款计划, 延迟任务, due-date job,
 * auto repayment scheduling, delay job, 支払日ジョブ(しはらいびジョブ),
 * 口座振替予定(こうざふりかえよてい), 遅延ジョブ(ちえんジョブ)。</p>
 *
 * <p>StatementService 只表达“账单到期日需要后续动作”这个业务意图；
 * 具体用 DelayJob 还是别的调度机制，由 adapter 决定。</p>
 */
public interface StatementDueJobScheduler {

    /**
     * 为已关闭 statement 计划到期自动扣款动作。
     *
     * <p>当前 adapter 写入 DelayJob；方法名保留业务语义，避免 StatementService 依赖
     * delay_jobs 表结构。</p>
     */
    void scheduleAutoRepayment(Statement statement);
}
