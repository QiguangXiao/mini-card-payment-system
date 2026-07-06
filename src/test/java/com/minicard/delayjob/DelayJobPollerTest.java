package com.minicard.delayjob;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayJobPollerTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    // 测试目的：验证 poller 不执行业务 handler，只把已 claim job 提交给 worker executor。
    // variant：SyncTaskExecutor 让提交同步执行，断言 worker.handleClaimedJob 被调用。
    void submitsClaimedJobsToWorkerExecutor() {
        DelayJobClaimer claimer = mock(DelayJobClaimer.class);
        DelayJobWorker worker = mock(DelayJobWorker.class);
        DelayJob job = pendingJob(DelayJobType.AUTHORIZATION_EXPIRY, "Authorization");
        when(claimer.claimDueJobs()).thenReturn(List.of(job));
        DelayJobPoller poller = new DelayJobPoller(
                claimer,
                worker,
                new SyncTaskExecutor(),
                new SyncTaskExecutor()
        );

        poller.pollDueJobs();

        verify(claimer).claimDueJobs();
        verify(worker).handleClaimedJob(job);
    }

    @Test
    // 测试目的：验证按 jobType 分池路由——外部银行调用类 job 与纯 DB 类 job 不共线程池。
    // variant：一批混领 AUTHORIZATION_EXPIRY + AUTO_REPAYMENT，各自落到对应 executor。
    void routesAutoRepaymentJobsToDedicatedExecutor() {
        DelayJobClaimer claimer = mock(DelayJobClaimer.class);
        DelayJobWorker worker = mock(DelayJobWorker.class);
        DelayJob expiryJob = pendingJob(DelayJobType.AUTHORIZATION_EXPIRY, "Authorization");
        DelayJob autoRepayJob = pendingJob(DelayJobType.AUTO_REPAYMENT, "Statement");
        when(claimer.claimDueJobs()).thenReturn(List.of(expiryJob, autoRepayJob));
        // 记录型池：提交是同步执行的，所以"当前在哪个池"在 worker mock 被调用时仍然有效。
        AtomicReference<String> currentPool = new AtomicReference<>();
        TaskExecutor internalPool = task -> {
            currentPool.set("internal");
            task.run();
        };
        TaskExecutor autoRepayPool = task -> {
            currentPool.set("autoRepay");
            task.run();
        };
        Map<UUID, String> poolByJobId = new HashMap<>();
        doAnswer(invocation -> {
            DelayJob job = invocation.getArgument(0);
            poolByJobId.put(job.id(), currentPool.get());
            return null;
        }).when(worker).handleClaimedJob(any());
        DelayJobPoller poller = new DelayJobPoller(claimer, worker, internalPool, autoRepayPool);

        poller.pollDueJobs();

        // 授权过期走内部池，自动扣款走专用池：银行 brownout 只会钉住专用池，
        // 授权额度释放不受连带影响——这就是拆池要保护的性质。
        assertThat(poolByJobId.get(expiryJob.id())).isEqualTo("internal");
        assertThat(poolByJobId.get(autoRepayJob.id())).isEqualTo("autoRepay");
    }

    private DelayJob pendingJob(DelayJobType jobType, String aggregateType) {
        return DelayJob.pending(
                UUID.randomUUID(),
                jobType,
                aggregateType,
                UUID.randomUUID().toString(),
                NOW,
                NOW
        );
    }
}
