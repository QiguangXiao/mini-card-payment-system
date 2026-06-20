package com.minicard.statement.application;

import java.time.LocalDate;

/**
 * 业务日历抽象，负责判断某天是否为可执行扣款/清算的 business day（営業日）。
 *
 * <p>关键词：业务日历, 营业日, 顺延, business day, calendar, roll forward,
 * 営業日(えいぎょうび), 祝日(しゅくじつ), 翌営業日(よくえいぎょうび)。</p>
 *
 * <p>Statement batch 只依赖这个接口，不直接写日本节假日规则；这样未来如果改成银行提供的
 * holiday master（祝日マスター），只需要替换实现 bean。</p>
 */
public interface BusinessDayCalendar {

    /**
     * 判断给定日期是否可作为银行扣款日。
     *
     * <p>实现类需要把 weekend、public holiday（祝日）和银行非营业日都算进去。</p>
     */
    boolean isBusinessDay(LocalDate date);

    /**
     * 从目标日期开始向后找第一个 business day（翌営業日）。
     *
     * <p>这是 Java interface default method，避免每个日历实现重复写“周末/节假日顺延”的循环逻辑。</p>
     */
    default LocalDate nextBusinessDayOnOrAfter(LocalDate date) {
        LocalDate candidate = date;
        while (!isBusinessDay(candidate)) {
            // 扣款日不能落在休业日；遇到周末或祝日时顺延到此后的第一个営業日。
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }
}
