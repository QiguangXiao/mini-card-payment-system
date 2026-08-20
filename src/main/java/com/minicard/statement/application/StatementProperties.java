package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Statement 模块的统一配置。
 *
 * <p>关键词：账单配置, 批次任务, 最低还款, statement properties,
 * billing batch, minimum payment, 請求設定(せいきゅうせってい)。</p>
 *
 * <p>mini-card 是学习项目，所以把 batch/policy 放在一个配置类里；
 * 仍保留清晰的 nested records，避免配置散落成多个小类。</p>
 */
@ConfigurationProperties(prefix = "statement")
public record StatementProperties(
        /** 月次出账批处理的触发与账期配置。 */
        Batch batch,
        /** 最低还款额计算规则。 */
        Policy policy
) {

    public StatementProperties {
        // 两段配置缺任意一段都不能静默使用零值：零值可能关闭调度或算错最低还款额。
        if (batch == null || policy == null) {
            throw new IllegalArgumentException("statement properties sections must be configured");
        }
    }

    /**
     * 出账批处理配置：什么时候跑、按哪个时区切账期、扣款日怎么定。
     */
    public record Batch(
            /** 是否启用 BillingCycleScheduler；关闭后不会自动出账（集成测试就是这么关的）。 */
            boolean enabled,
            /** scheduler cron，每天触发一次出账批处理。 */
            String cron,
            /** 账务日切时区，同时用于 cron 和 LocalDate 到 Instant 的边界转换。 */
            String zone,
            /** 次月计划付款日，遇非营业日再由 BusinessDayCalendar 顺延。 */
            int paymentBaseDayOfMonth,
            /**
             * 银行休业日（祝日マスター/holiday master）数据，由 ConfiguredBusinessDayCalendar 使用。
             *
             * <p>祝日是数据不是算法：祝日法会修改、春分/秋分每年官方公布，所以放配置而不是写死在代码里。
             * 缺某一年数据时日历会 fail-open 成"只跳周末"并打 warn 日志，不会让批处理停摆。</p>
             */
            Set<LocalDate> bankHolidays
    ) {
        public Batch {
            // 未配置时按空集合处理：日历退化成"只跳周末"，并由 ConfiguredBusinessDayCalendar 告警。
            // Set.copyOf 顺手做不可变防御拷贝，避免绑定后的集合被别处修改影响扣款日推导。
            bankHolidays = bankHolidays == null ? Set.of() : Set.copyOf(bankHolidays);
            // planner 参数在应用启动绑定时 fail fast；运行中才发现无效时区/负分片规模会留下缺失 cycle。
            requireText(cron, "cron");
            requireText(zone, "zone");
            validateDay(paymentBaseDayOfMonth, "paymentBaseDayOfMonth");
        }
    }

    /**
     * 最低还款额策略：{@code min(total, max(total * rate, currency floor))}。
     */
    public record Policy(
            /** 按账单总额计算的比例，例如 0.10 表示 10%。 */
            BigDecimal minimumPaymentRate,
            /** 各币种最低金额下限；币种缺失时无法安全生成该币种账单。 */
        Map<String, BigDecimal> minimumPaymentFloors
    ) {
        public Policy {
            // 当前只检查配置结构完整；缺币种或非法 rate/floor 会在生成账单时由 service/domain 拒绝。
            // 这是一个可见的简化边界：生产系统更适合在启动阶段遍历所有币种并 fail fast，避免运行到月末才暴露配置错。
            if (minimumPaymentRate == null || minimumPaymentFloors == null || minimumPaymentFloors.isEmpty()) {
                throw new IllegalArgumentException("statement policy must be configured");
            }
        }
    }

    private static void validateDay(int day, String fieldName) {
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 31");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
