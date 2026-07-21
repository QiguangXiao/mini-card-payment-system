package com.minicard.statement.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 生成账单 use case 的输入 command。
 *
 * <p>真实主路径由 StatementJobHandler 按 StatementCycleService 规划的 billing cycle 自动构造。
 * 当前没有公开的手动 generate API；测试或未来受控的运营 backfill 也应复用这个 application command，
 * 不能绕过 StatementGenerationService 的 transaction boundary、row lock 和幂等保护。</p>
 *
 * <p>关键词：账单生成命令, 账期, 到期日, statement generation command,
 * billing period, due date, 請求作成コマンド(せいきゅうさくせいコマンド)。</p>
 */
public record GenerateStatementCommand(
        /** 要生成账单的信用账户；一个账户在同一账期最多生成一张 statement。 */
        UUID creditAccountId,
        /** 账期首日，和 periodEnd 一起形成闭区间业务日期。 */
        LocalDate periodStart,
        /** 账期关账日；查询交易时会转换为 billing timezone 下的次日零点 exclusive bound。 */
        LocalDate periodEnd,
        /** 付款到期日，必须晚于关账日，并会用于计划 AUTO_REPAYMENT DelayJob。 */
        LocalDate dueDate
) {

    public GenerateStatementCommand {
        // Command invariant 不能只依赖 scheduler：测试、batch/backfill 等非 HTTP 路径也能直接构造它。
        // 如果允许反向账期或 dueDate 不晚于关账日，后续 SQL 时间窗口和自动扣款计划都会失真。
        Objects.requireNonNull(creditAccountId, "creditAccountId must not be null");
        Objects.requireNonNull(periodStart, "periodStart must not be null");
        Objects.requireNonNull(periodEnd, "periodEnd must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not be before periodStart");
        }
        if (!dueDate.isAfter(periodEnd)) {
            throw new IllegalArgumentException("dueDate must be after periodEnd");
        }
    }
}
