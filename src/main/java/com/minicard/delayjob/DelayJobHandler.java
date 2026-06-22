package com.minicard.delayjob;

/**
 * DelayJob 的业务 handler 端口。
 *
 * <p>关键词：延迟任务处理器, 任务分发, 业务执行, delay job handler,
 * job dispatch, business action, 遅延ジョブハンドラー(ちえんジョブハンドラー),
 * 業務処理(ぎょうむしょり)。</p>
 */
public interface DelayJobHandler {

    /**
     * 声明当前 handler 负责的 job type，worker 会据此构建 dispatch map。
     */
    // Worker 启动时可以把 List<DelayJobHandler> 收集成 Map<DelayJobType, Handler>。
    // 如果用字符串 switch 分发，新增 job type 时容易漏改核心 worker。
    DelayJobType jobType();

    /**
     * 执行业务动作。
     *
     * <p>异常会被 DelayJobWorker 捕获并转换为 retry/DEAD 状态，handler 不需要自己更新 delay_jobs。</p>
     */
    void handle(DelayJob job);
}
