package com.minicard.authorization.application;

import java.util.UUID;

import com.minicard.scheduling.application.DelayJobHandler;
import com.minicard.scheduling.domain.DelayJob;
import com.minicard.scheduling.domain.DelayJobType;
import org.springframework.stereotype.Component;

/**
 * Authorization expiry 的 DelayJob handler，把通用延迟任务分发到具体业务用例。
 *
 * <p>面试重点：ScheduledJobWorker 不认识授权业务，只按 jobType 找 handler；
 * 这样 scheduler 是通用机制，业务动作仍留在 authorization application layer。</p>
 */
@Component
public class AuthorizationExpiryDelayJobHandler implements DelayJobHandler {

    private final AuthorizationExpiryService expiryService;

    public AuthorizationExpiryDelayJobHandler(AuthorizationExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Override
    public DelayJobType jobType() {
        // jobType() 是 ScheduledJobWorker 分发(handler dispatch)的 key。
        return DelayJobType.AUTHORIZATION_EXPIRY;
    }

    @Override
    public void handle(DelayJob job) {
        // DelayJob 只保存通用 aggregateId；handler 把它转换成业务用的 authorizationId。
        expiryService.expire(UUID.fromString(job.aggregateId()));
    }
}
