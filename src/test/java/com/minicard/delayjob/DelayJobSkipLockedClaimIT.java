package com.minicard.delayjob;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.minicard.support.MySqlIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKIP LOCKED 批量领取并发安全测试：多个 worker 并发 claim 同一批到期 delay job，断言无重复领取。
 *
 * <p>关键词：任务领取, 重复领取, 行锁跳过, claim, exactly-once dispatch,
 * FOR UPDATE SKIP LOCKED, ジョブ二重取得防止, 行ロックスキップ。</p>
 *
 * <p>命题：N 个 PENDING job，K 个 poller 同时抢，每个 job 必须恰好被一个 worker 领走。
 * {@link DelayJobClaimer#claimDueJobs()} 在一个短事务里 {@code FOR UPDATE SKIP LOCKED} 选行、
 * 立刻改成 PROCESSING lease 再提交——SKIP LOCKED 让并发 poller 跳过别人已锁住的行，
 * 因此不会两个 worker 拿到同一个 job。</p>
 *
 * <p>反事实：若改成普通 {@code FOR UPDATE}（不带 SKIP LOCKED），并发 poller 会互相阻塞、
 * 吞吐塌成串行；若连锁都不加，两个 poller 会读到同一批 PENDING 行并各自标 PROCESSING，
 * 同一个 future business action 被执行两次。这里的“每个 id 只被领取一次”正是防重的证据。</p>
 */
class DelayJobSkipLockedClaimIT extends MySqlIntegrationTestBase {

    private static final int JOB_COUNT = 300;
    private static final int WORKERS = 8;

    @Autowired
    private DelayJobClaimer delayJobClaimer;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedDueJobs() {
        // 清掉历史 row，保证本次断言的 PROCESSING 计数只反映本测试插入的任务。
        jdbc.update("DELETE FROM delay_jobs");
        // due 取 2 天前：远大于任何 JVM/MySQL 会话时区偏移（±14h），彻底排除“时区把到期行算成未来行”的干扰。
        Instant due = Instant.now().minus(2, ChronoUnit.DAYS);
        Timestamp dueTs = Timestamp.from(due);
        List<Object[]> rows = new java.util.ArrayList<>(JOB_COUNT);
        for (int i = 0; i < JOB_COUNT; i++) {
            rows.add(new Object[]{
                    UUID.randomUUID().toString(),
                    "AUTHORIZATION_EXPIRY",
                    "AUTHORIZATION",
                    UUID.randomUUID().toString(), // aggregate_id 唯一，避开 uk_delay_jobs_aggregate
                    "PENDING",
                    0,
                    dueTs, dueTs, dueTs, dueTs
            });
        }
        jdbc.batchUpdate(
                "INSERT INTO delay_jobs (id, job_type, aggregate_type, aggregate_id, status, attempts, "
                        + "scheduled_at, next_attempt_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rows);
    }

    @Test
    void concurrentClaimersNeverClaimTheSameJobTwice() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(WORKERS);
        // id -> 被领取次数；正确实现下每个 id 恰好出现一次。
        ConcurrentHashMap<String, Integer> claimCounts = new ConcurrentHashMap<>();
        AtomicInteger totalClaimed = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        for (int w = 0; w < WORKERS; w++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // SKIP LOCKED 下某个 poller 可能短暂看到空批（行被别人锁着），所以循环到全部领完为止。
                    while (totalClaimed.get() < JOB_COUNT && System.nanoTime() < deadlineNanos) {
                        List<DelayJob> batch = delayJobClaimer.claimDueJobs();
                        if (batch.isEmpty()) {
                            Thread.sleep(2);
                            continue;
                        }
                        for (DelayJob job : batch) {
                            claimCounts.merge(job.id().toString(), 1, Integer::sum);
                            totalClaimed.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    // 捕获所有异常并上报：claim 期间任何异常都说明锁/事务/映射有问题，不能被 executor 静默吞掉。
                    if (t instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    failures.add(t);
                } finally {
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = finished.await(40, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(completed).as("all claimers finished within timeout").isTrue();
        assertThat(failures).as("no claimer threw during FOR UPDATE SKIP LOCKED claim").isEmpty();
        assertThat(totalClaimed.get())
                .as("every job claimed exactly once across all workers")
                .isEqualTo(JOB_COUNT);
        assertThat(claimCounts).hasSize(JOB_COUNT);
        assertThat(claimCounts.values())
                .as("no job id was returned to two different workers (SKIP LOCKED prevents double-claim)")
                .allMatch(count -> count == 1);

        Integer processing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delay_jobs WHERE status = 'PROCESSING'", Integer.class);
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delay_jobs WHERE status = 'PENDING'", Integer.class);
        assertThat(processing).as("all claimed jobs flipped to PROCESSING lease").isEqualTo(JOB_COUNT);
        assertThat(pending).as("no job left unclaimed").isZero();
    }
}
