package com.minicard.creditaccount.domain;

import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

public final class CreditAccount {

    private final UUID id;
    private final Money creditLimit;
    private Money reservedAmount;
    private CreditAccountStatus status;

    private CreditAccount(
            UUID id,
            Money creditLimit,
            Money reservedAmount,
            CreditAccountStatus status
    ) {
        this.id = Objects.requireNonNull(id);
        this.creditLimit = Objects.requireNonNull(creditLimit);
        this.reservedAmount = Objects.requireNonNull(reservedAmount);
        this.status = Objects.requireNonNull(status);
        validateState();
    }

    public static CreditAccount restore(
            UUID id,
            Money creditLimit,
            Money reservedAmount,
            CreditAccountStatus status
    ) {
        return new CreditAccount(id, creditLimit, reservedAmount, status);
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

    public Money availableCredit() {
        return creditLimit.subtract(reservedAmount);
    }

    private void validateState() {
        // invariant 同时保护 DB restore 和未来 factory method，避免 impossible balance 进入领域模型。
        if (!creditLimit.currency().equals(reservedAmount.currency())) {
            throw new IllegalArgumentException("credit limit and reserved amount currencies differ");
        }
        if (reservedAmount.isGreaterThan(creditLimit)) {
            throw new IllegalArgumentException("reserved amount cannot exceed credit limit");
        }
    }

    public UUID id() {
        return id;
    }

    public Money creditLimit() {
        return creditLimit;
    }

    public Money reservedAmount() {
        return reservedAmount;
    }

    public CreditAccountStatus status() {
        return status;
    }
}
