package com.minicard.creditaccount.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 额度占用结果。
 *
 * <p>关键词：额度占用, reservation, 可用额度, credit reservation result,
 * available credit, failure reason, 利用可能額確保結果(りようかのうがくかくほけっか),
 * 利用可能枠(りようかのうわく)。</p>
 */
public record CreditReservationResult(
        /** true 表示 reservation 成功。 */
        boolean reserved,
        /** 失败原因；成功时为空。 */
        CreditReservationFailure failure
) {

    // compact constructor 让 success/rejected 两个静态工厂之外的直接 new 也守住约束。
    // 如果只靠工厂方法，测试或未来代码仍可能 new CreditReservationResult(true, failure)。
    public CreditReservationResult {
        if (reserved && failure != null) {
            // 成功 reservation 不能携带失败原因，避免上层误判。
            throw new IllegalArgumentException("successful reservation cannot have a failure");
        }
        if (!reserved) {
            Objects.requireNonNull(failure, "failed reservation requires a reason");
        }
    }

    /**
     * 构造成功结果。
     */
    public static CreditReservationResult success() {
        return new CreditReservationResult(true, null);
    }

    /**
     * 构造失败结果。
     */
    public static CreditReservationResult rejected(CreditReservationFailure failure) {
        return new CreditReservationResult(false, failure);
    }

    /**
     * Optional 包装失败原因，避免调用方直接处理 null。
     */
    // Optional 只用于返回值，不用于字段；字段保持简单，调用方读取时再表达“可能没有失败原因”。
    public Optional<CreditReservationFailure> optionalFailure() {
        return Optional.ofNullable(failure);
    }
}
