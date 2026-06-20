package com.minicard.delayjob.mybatis;

import java.time.Instant;

/**
 * delay_jobs 表的 MyBatis row DTO。
 *
 * <p>关键词：延迟任务行, 租约截止, 重试次数, delay job row,
 * lease deadline, attempts, 遅延ジョブ行(ちえんジョブぎょう),
 * リース期限(リースきげん)。</p>
 *
 * <p>Row 只表达数据库字段；DelayJob aggregate 负责状态迁移规则。</p>
 */
public record DelayJobRow(
        /** delay job 主键。 */
        String id,
        /** DelayJobType 字符串。 */
        String jobType,
        /** 目标聚合类型，例如 Statement。 */
        String aggregateType,
        /** 目标聚合 id。 */
        String aggregateId,
        /** DelayJobStatus 字符串。 */
        String status,
        /** 已尝试次数。 */
        int attempts,
        /** 业务计划执行时间。 */
        Instant scheduledAt,
        /** 下次可执行时间；PROCESSING 时临时表示 lease deadline。 */
        Instant nextAttemptAt,
        /** 创建时间。 */
        Instant createdAt,
        /** 更新时间。 */
        Instant updatedAt,
        /** 最近一次失败原因。 */
        String lastError
) {
}
