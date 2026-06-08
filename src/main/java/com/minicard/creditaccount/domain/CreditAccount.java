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
        // A blocked account can reject every card linked to it. This is distinct
        // from a blocked Card, which rejects only that single card.
        if (status != CreditAccountStatus.ACTIVE) {
            return CreditReservationResult.rejected(CreditReservationFailure.ACCOUNT_BLOCKED);
        }
        // Currency mismatch is a domain failure. Without this check, arithmetic
        // like JPY limit minus USD charge would look valid but be meaningless.
        if (!creditLimit.currency().equals(amount.currency())) {
            return CreditReservationResult.rejected(CreditReservationFailure.CURRENCY_MISMATCH);
        }
        // Available credit is computed from the aggregate state, and the row is
        // locked by the repository before this method is called. That pairing is
        // what makes the check safe under concurrent authorizations.
        if (amount.isGreaterThan(availableCredit())) {
            return CreditReservationResult.rejected(
                    CreditReservationFailure.INSUFFICIENT_AVAILABLE_CREDIT
            );
        }

        reservedAmount = reservedAmount.add(amount);
        return CreditReservationResult.success();
    }

    public Money availableCredit() {
        return creditLimit.subtract(reservedAmount);
    }

    private void validateState() {
        // These invariants protect both newly loaded database rows and future
        // factory methods from representing impossible account balances.
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
