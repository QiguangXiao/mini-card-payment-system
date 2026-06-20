package com.minicard.creditaccount.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 信用账户 aggregate root，负责维护总额度、已预占额度、已入账余额和账户状态。
 *
 * <p>关键词：额度账户聚合, 可用额度, 行锁, credit account aggregate,
 * available credit, row lock, 利用枠管理集約(りようわくかんりしゅうやく),
 * 利用可能枠(りようかのうわく)。</p>
 *
 * <p>interview重点：高并发授权不是靠 JVM synchronized，而是 service 先拿 DB row lock，
 * 再调用这个 aggregate 的 reserve/release 来保护额度 invariant。</p>
 */
@Getter
@Accessors(fluent = true)
public final class CreditAccount {

    private final UUID id;
    private final Money creditLimit;
    private Money reservedAmount;
    private Money postedBalance;
    private CreditAccountStatus status;

    private CreditAccount(
            UUID id,
            Money creditLimit,
            Money reservedAmount,
            Money postedBalance,
            CreditAccountStatus status
    ) {
        this.id = Objects.requireNonNull(id);
        this.creditLimit = Objects.requireNonNull(creditLimit);
        this.reservedAmount = Objects.requireNonNull(reservedAmount);
        this.postedBalance = Objects.requireNonNull(postedBalance);
        this.status = Objects.requireNonNull(status);
        validateState();
    }

    public static CreditAccount restore(
            UUID id,
            Money creditLimit,
            Money reservedAmount,
            CreditAccountStatus status
    ) {
        return restore(
                id,
                creditLimit,
                reservedAmount,
                zero(creditLimit.currency()),
                status
        );
    }

    public static CreditAccount restore(
            UUID id,
            Money creditLimit,
            Money reservedAmount,
            Money postedBalance,
            CreditAccountStatus status
    ) {
        return new CreditAccount(id, creditLimit, reservedAmount, postedBalance, status);
    }

    public CreditReservationResult reserve(Money amount) {
        Objects.requireNonNull(amount);
        // ACTIVE check 是账户级开关：blocked account 会拒绝该账户下所有卡，区别于单张 Card blocked。
        if (status != CreditAccountStatus.ACTIVE) {
            return CreditReservationResult.rejected(CreditReservationFailure.ACCOUNT_BLOCKED);
        }
        // currency mismatch 是 domain failure；JPY limit 减 USD amount 在业务上没有意义。
        if (!creditLimit.currency().equals(amount.currency())) {
            return CreditReservationResult.rejected(CreditReservationFailure.CURRENCY_MISMATCH);
        }
        // availableCredit() 来自 aggregate 当前状态；service 在调用前已经拿到 DB row lock。
        // “row lock + domain invariant”配合，才能在并发 authorization 下安全预占额度。
        if (amount.isGreaterThan(availableCredit())) {
            return CreditReservationResult.rejected(
                    CreditReservationFailure.INSUFFICIENT_AVAILABLE_CREDIT
            );
        }

        reservedAmount = reservedAmount.add(amount);
        return CreditReservationResult.success();
    }

    public void release(Money amount) {
        Objects.requireNonNull(amount);
        // release() 是 expiry 对已存在 reservation 的补偿动作(compensating action)。
        // currency mismatch 或 underflow 代表状态损坏，直接失败并 rollback 比静默修正更安全。
        if (!creditLimit.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("release currency must match account currency");
        }
        if (amount.isGreaterThan(reservedAmount)) {
            throw new IllegalStateException("release amount exceeds reserved amount");
        }
        reservedAmount = reservedAmount.subtract(amount);
    }

    public void postAuthorized(Money amount) {
        Objects.requireNonNull(amount);
        // Posting 是 issuer 入账动作：把 authorization hold 从 reservedAmount 移到 postedBalance。
        // 这里要求 amount <= reservedAmount，先支持最常见的 full presentment，避免现在就引入 partial capture。
        if (!creditLimit.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("posting currency must match account currency");
        }
        if (amount.isGreaterThan(reservedAmount)) {
            throw new IllegalStateException("posting amount exceeds reserved amount");
        }
        reservedAmount = reservedAmount.subtract(amount);
        postedBalance = postedBalance.add(amount);
    }

    public void applyRepayment(Money amount) {
        Objects.requireNonNull(amount);
        // Repayment 是持卡人还款入账：它释放已入账消费占用的信用额度。
        // service 会先拿 credit account row lock，再调用这里，避免并发 repayment/posting 改乱 postedBalance。
        if (!creditLimit.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("repayment currency must match account currency");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("repayment amount must be positive");
        }
        if (amount.isGreaterThan(postedBalance)) {
            throw new IllegalStateException("repayment amount exceeds posted balance");
        }
        postedBalance = postedBalance.subtract(amount);
    }

    public Money availableCredit() {
        // available credit 是派生值，不单独落库，避免 creditLimit/reserved/posted/available 多字段不一致。
        // issuer 视角下，reserved hold 和 posted balance 都会占用信用额度。
        return creditLimit.subtract(reservedAmount).subtract(postedBalance);
    }

    private void validateState() {
        // invariant 同时保护 DB restore 和未来 factory method，避免 impossible balance 进入领域模型。
        if (!creditLimit.currency().equals(reservedAmount.currency())
                || !creditLimit.currency().equals(postedBalance.currency())) {
            throw new IllegalArgumentException("credit account money currencies differ");
        }
        if (reservedAmount.add(postedBalance).isGreaterThan(creditLimit)) {
            throw new IllegalArgumentException("reserved amount and posted balance cannot exceed credit limit");
        }
    }

    private static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }
}
