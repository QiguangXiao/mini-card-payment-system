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
 * <p>DelayJobWorker 只懂 jobType dispatch；真正的“银行扣款成功后入账”业务仍在 repayment application layer。</p>
 */
@Component
@RequiredArgsConstructor
public class AutoRepaymentDelayJobHandler implements DelayJobHandler {

    private static final String AGGREGATE_TYPE = "Statement";

    private final AutoRepaymentService autoRepaymentService;

    @Override
    public DelayJobType jobType() {
        return DelayJobType.AUTO_REPAYMENT;
    }

    @Override
    public void handle(DelayJob job) {
        if (!AGGREGATE_TYPE.equals(job.aggregateType())) {
            throw new IllegalArgumentException("AUTO_REPAYMENT job must target Statement aggregate");
        }
        autoRepaymentService.debitStatement(UUID.fromString(job.aggregateId()));
    }
}
