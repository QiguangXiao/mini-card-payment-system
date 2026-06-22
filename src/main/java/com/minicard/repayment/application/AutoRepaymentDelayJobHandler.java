package com.minicard.repayment.application;

import java.util.UUID;

import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobHandler;
import com.minicard.delayjob.DelayJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AUTO_REPAYMENT DelayJob handler。
 *
 * <p>关键词：自动还款任务, 任务分发, 失败重试, auto repayment handler,
 * job dispatch, retry, 自動引き落としジョブ(じどうひきおとしジョブ),
 * ジョブ振り分け(ジョブふりわけ), リトライ。</p>
 *
 * <p>DelayJobWorker 只懂 jobType dispatch；真正的“银行扣款成功后入账”业务仍在 repayment application layer。</p>
 */
@Component
@RequiredArgsConstructor
public class AutoRepaymentDelayJobHandler implements DelayJobHandler {

    /** DelayJob aggregate_type 必须和 statement 计划写入时一致，用于防止错误 jobType/aggregate 混用。 */
    private static final String AGGREGATE_TYPE = "Statement";

    /** 真实自动扣款业务入口；handler 自己只做 job contract 校验和 dispatch。 */
    private final AutoRepaymentService autoRepaymentService;

    /**
     * 声明当前 handler 负责 AUTO_REPAYMENT，DelayJobWorker 会按这个类型建立 dispatch map。
     */
    @Override
    public DelayJobType jobType() {
        return DelayJobType.AUTO_REPAYMENT;
    }

    /**
     * 执行 DelayJob。
     *
     * <p>这里先校验 aggregate_type，避免把其他聚合的 id 当成 statementId 解析，
     * 这类 defensive check 能让错误 job 更早失败并进入 retry/DEAD。</p>
     */
    @Override
    public void handle(DelayJob job) {
        if (!AGGREGATE_TYPE.equals(job.aggregateType())) {
            // 错误 contract 不能静默忽略，否则 job 可能被标 DONE 但业务没有发生。
            // 如果把任何 aggregateId 都当 statementId，错误 job 会在运行期变成难排查的数据错配。
            throw new IllegalArgumentException("AUTO_REPAYMENT job must target Statement aggregate");
        }
        // UUID 解析在 handler 边界完成；格式错误说明 delay job contract 坏了，应进入失败路径。
        autoRepaymentService.debitStatement(UUID.fromString(job.aggregateId()));
    }
}
