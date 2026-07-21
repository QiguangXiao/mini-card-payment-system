package com.minicard.authorization.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDeclinedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationExpiredDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationPostedDomainEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authorization aggregate 的生命周期、不变式和 domain event 测试。
 *
 * <p>关键词：授权状态机, 领域事件, 非法状态转换, authorization aggregate,
 * state transition, domain event, オーソリ状態遷移(オーソリじょうたいせんい)。</p>
 */
class AuthorizationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-07T00:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-06-07T00:00:01Z");

    @Test
    // 新请求必须从 PENDING 开始，不能在尚未决策时带 decline/decision 数据。
    void newAuthorizationStartsPending() {
        Authorization authorization = authorization();

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.PENDING);
        assertThat(authorization.declineReason()).isEmpty();
        assertThat(authorization.decidedAt()).isEmpty();
    }

    @Test
    // approve 同时推进状态、写决定/过期时间并产生一次性 APPROVED event。
    void approvesPendingAuthorization() {
        Authorization authorization = authorization();

        authorization.approve(DECIDED_AT);

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.APPROVED);
        assertThat(authorization.declineReason()).isEmpty();
        assertThat(authorization.decidedAt()).contains(DECIDED_AT);
        assertThat(authorization.expiresAt()).contains(DECIDED_AT.plusSeconds(7 * 24 * 60 * 60));
        assertThat(authorization.expiredAt()).isEmpty();
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationApprovedDomainEvent.class);
        assertThat(authorization.pullDomainEvents()).isEmpty();
    }

    @Test
    // 到达 expiresAt 后才能进入 EXPIRED，并产生供释放额度/通知使用的业务事实。
    void expiresApprovedAuthorizationAtOrAfterDeadline() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);
        authorization.pullDomainEvents();
        Instant expiryTime = DECIDED_AT.plusSeconds(7 * 24 * 60 * 60);

        authorization.expire(expiryTime);

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.EXPIRED);
        assertThat(authorization.expiredAt()).contains(expiryTime);
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationExpiredDomainEvent.class);
    }

    @Test
    // presentment 只能消费仍有效的 APPROVED authorization，成功后进入不可再次入账的 POSTED。
    void postsApprovedAuthorizationBeforeDeadline() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);
        authorization.pullDomainEvents();
        Instant postedAt = DECIDED_AT.plusSeconds(60);

        authorization.post(postedAt);

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.POSTED);
        assertThat(authorization.postedAt()).contains(postedAt);
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationPostedDomainEvent.class);
    }

    @Test
    // 过期时间未到不能提前释放 reservation，否则仍有效的授权可能失去额度保证。
    void rejectsExpiryBeforeDeadline() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);

        assertThatThrownBy(() -> authorization.expire(DECIDED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authorization cannot expire before expiresAt");
    }

    @Test
    // decline 必须保存机器可读原因并只发布一次 DECLINED event。
    void declinesPendingAuthorizationWithReason() {
        Authorization authorization = authorization();

        authorization.decline(
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED,
                DECIDED_AT
        );

        assertThat(authorization.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(authorization.declineReason())
                .contains(AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED);
        assertThat(authorization.decidedAt()).contains(DECIDED_AT);
        assertThat(authorization.pullDomainEvents())
                .singleElement()
                .isInstanceOf(AuthorizationDeclinedDomainEvent.class);
    }

    @Test
    // 授权只能决策一次；APPROVED 后再 DECLINE 会制造互相矛盾的资金状态。
    void rejectsSecondDecision() {
        Authorization authorization = authorization();
        authorization.approve(DECIDED_AT);

        assertThatThrownBy(() -> authorization.decline(
                AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED,
                DECIDED_AT
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot decline authorization in status APPROVED");
    }

    @Test
    // 金额正数不变式放在 aggregate factory，保护非 HTTP 的 scheduler/test/restore 调用路径。
    void rejectsZeroAuthorizationAmount() {
        assertThatThrownBy(() -> Authorization.request(
                "fingerprint-1",
                "card-123",
                new Money(BigDecimal.ZERO, Currency.getInstance("JPY")),
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authorization amount must be greater than zero");
    }

    private Authorization authorization() {
        return Authorization.request(
                "fingerprint-1",
                "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                CREATED_AT
        );
    }
}
