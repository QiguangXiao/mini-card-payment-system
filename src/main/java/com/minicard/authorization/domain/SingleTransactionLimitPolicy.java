package com.minicard.authorization.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;

public final class SingleTransactionLimitPolicy implements AuthorizationDecisionPolicy {

    private final Map<Currency, Money> limits;

    public SingleTransactionLimitPolicy(Map<Currency, BigDecimal> limits) {
        Objects.requireNonNull(limits);
        this.limits = limits.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> new Money(entry.getValue(), entry.getKey())
                ));
    }

    @Override
    public AuthorizationDecision decide(Authorization authorization) {
        Money limit = limits.get(authorization.requestedAmount().currency());
        if (limit == null) {
            return AuthorizationDecision.decline(AuthorizationDeclineReason.UNSUPPORTED_CURRENCY);
        }
        if (authorization.requestedAmount().isGreaterThan(limit)) {
            return AuthorizationDecision.decline(
                    AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED
            );
        }
        return AuthorizationDecision.approve();
    }
}
