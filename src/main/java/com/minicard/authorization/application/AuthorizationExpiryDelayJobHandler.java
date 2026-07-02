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
// @RequiredArgsConstructor 适合这种纯 dispatch adapter；没有额外初始化逻辑，只需要注入业务 service。
@RequiredArgsConstructor
public class AuthorizationExpiryDelayJobHandler implements DelayJobHandler {

    /** DelayJob aggregate_type 必须和计划写入时一致，避免把其他聚合 id 当成 authorizationId。 */
    private static final String AGGREGATE_TYPE = "Authorization";

    private final AuthorizationExpiryService expiryService;

    /**
     * 声明当前 handler 负责 AUTHORIZATION_EXPIRY 类型任务。
     */
    @Override
    public DelayJobType jobType() {
        // jobType() 是 DelayJobWorker 分发(handler dispatch)的 key。
        return DelayJobType.AUTHORIZATION_EXPIRY;
    }

    /**
     * 校验 DelayJob contract，并把 aggregateId 分发给授权过期 use case。
     *
     * <p>事务归属：本 handler 自己不开事务；它调用的
     * {@link AuthorizationExpiryService#expire(UUID)} 是另一个 Spring bean 的 public
     * {@code @Transactional} 方法，会通过 proxy 开启授权过期事务。</p>
     */
    @Override
    public void handle(DelayJob job) {
        if (!AGGREGATE_TYPE.equals(job.aggregateType())) {
            // 错误 contract 不能静默忽略，否则 job 可能被标 DONE 但授权额度没有释放。
            throw new IllegalArgumentException("AUTHORIZATION_EXPIRY job must target Authorization aggregate");
        }
        // DelayJob 只保存通用 aggregateId；handler 把它转换成业务用的 authorizationId。
        // UUID.fromString 也属于 contract validation，格式坏的 job 会失败并进入 retry/DEAD。
        expiryService.expire(UUID.fromString(job.aggregateId()));
    }
}
