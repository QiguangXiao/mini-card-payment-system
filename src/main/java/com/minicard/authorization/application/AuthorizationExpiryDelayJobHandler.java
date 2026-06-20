package com.minicard.authorization.application;

import java.util.UUID;

import com.minicard.delayjob.DelayJobHandler;
import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Authorization expiry 的 DelayJob handler，把通用延迟任务分发到具体业务用例。
 *
 * <p>interview重点：DelayJobWorker 不认识授权业务，只按 jobType 找 handler；
 * 这样 scheduler 是通用机制，业务动作仍留在 authorization application layer。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthorizationExpiryDelayJobHandler implements DelayJobHandler {

    /** DelayJob aggregate_type 必须和计划写入时一致，避免把其他聚合 id 当成 authorizationId。 */
    private static final String AGGREGATE_TYPE = "Authorization";

    private final AuthorizationExpiryService expiryService;

    @Override
    public DelayJobType jobType() {
        // jobType() 是 DelayJobWorker 分发(handler dispatch)的 key。
        return DelayJobType.AUTHORIZATION_EXPIRY;
    }

    @Override
    public void handle(DelayJob job) {
        if (!AGGREGATE_TYPE.equals(job.aggregateType())) {
            // 错误 contract 不能静默忽略，否则 job 可能被标 DONE 但授权额度没有释放。
            throw new IllegalArgumentException("AUTHORIZATION_EXPIRY job must target Authorization aggregate");
        }
        // DelayJob 只保存通用 aggregateId；handler 把它转换成业务用的 authorizationId。
        expiryService.expire(UUID.fromString(job.aggregateId()));
    }
}
