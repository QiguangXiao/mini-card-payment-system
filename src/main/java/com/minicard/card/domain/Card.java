package com.minicard.card.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 发卡行侧的一张卡，指向共享的 CreditAccount。
 *
 * <p>一张信用账户可以有多张卡，所以 Card 负责生命周期可用性，CreditAccount 负责额度。
 * 这个拆分是 PayPay Card 面试里很容易被问到的 domain modeling 点。</p>
 */
public record Card(
        String id,
        UUID creditAccountId,
        CardStatus status
) {

    public Card {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("card id must not be blank");
        }
        Objects.requireNonNull(creditAccountId);
        Objects.requireNonNull(status);
    }

    public CardAuthorizationResult checkAuthorizationEligibility() {
        // 先检查 Card lifecycle，再碰 credit account。
        // blocked/expired card 不应该占用账户 row lock 时间。
        return switch (status) {
            case ACTIVE -> CardAuthorizationResult.allowed();
            case BLOCKED -> CardAuthorizationResult.rejected(
                    CardAuthorizationFailure.CARD_BLOCKED
            );
            case EXPIRED -> CardAuthorizationResult.rejected(
                    CardAuthorizationFailure.CARD_EXPIRED
            );
        };
    }
}
