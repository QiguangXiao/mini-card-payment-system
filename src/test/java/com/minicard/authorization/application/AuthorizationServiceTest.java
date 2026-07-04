package com.minicard.authorization.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationApprovedDomainEvent;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.domain.CardStatus;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditAccountStatus;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T00:00:00Z");

    private AuthorizationRepository authorizationRepository;
    private CardRepository cardRepository;
    private CreditAccountRepository creditAccountRepository;
    private RiskAssessmentService riskAssessmentService;
    private AuthorizationDomainEventPublisher eventPublisher;
    private AuthorizationExpiryJobScheduler expiryJobScheduler;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        authorizationRepository = mock(AuthorizationRepository.class);
        cardRepository = mock(CardRepository.class);
        creditAccountRepository = mock(CreditAccountRepository.class);
        riskAssessmentService = mock(RiskAssessmentService.class);
        eventPublisher = mock(AuthorizationDomainEventPublisher.class);
        expiryJobScheduler = mock(AuthorizationExpiryJobScheduler.class);
        when(riskAssessmentService.assess(any())).thenReturn(RiskDecision.approve(20));
        service = new AuthorizationService(
                authorizationRepository,
                new AuthorizationPolicyProperties(Map.of("JPY", new BigDecimal("100000.00"))),
                cardRepository,
                creditAccountRepository,
                riskAssessmentService,
                eventPublisher,
                expiryJobScheduler,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    // 测试目的：验证授权 happy path 会预占额度并产生 APPROVED 事件/过期 DelayJob。
    // variant：card active、risk approve、available credit 足够，服务应锁 account 后 reserve。
    void approvesAndReservesAvailableCredit() {
        arrangeNewClaim();
        CreditAccount account = activeAccount("1000.00", "0.00");
        Card card = activeCard("card-123", account.id());
        when(cardRepository.findById("card-123")).thenReturn(Optional.of(card));
        when(creditAccountRepository.findByIdForUpdate(account.id()))
                .thenReturn(Optional.of(account));

        Authorization result = service.authorize(command("key-1", "card-123", "100.00"));

        assertThat(result.status()).isEqualTo(AuthorizationStatus.APPROVED);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("100.00");
        verify(creditAccountRepository).update(account);
        verify(authorizationRepository).update(result);
        verify(expiryJobScheduler).schedule(result);
        ArgumentCaptor<AuthorizationDomainEvent> event =
                ArgumentCaptor.forClass(AuthorizationDomainEvent.class);
        verify(eventPublisher).append(event.capture());
        assertThat(event.getValue()).isInstanceOf(AuthorizationApprovedDomainEvent.class);
        assertThat(event.getValue().authorizationId()).isEqualTo(result.id());
    }

    @Test
    // 测试目的：验证单笔限额是 cheap policy check，会在查卡/锁账户前拒绝。
    // variant：金额超过 JPY limit，状态 DECLINED，且不触碰 card/account repository。
    void declinesWithoutReservingWhenPolicyRejectsRequest() {
        arrangeNewClaim();

        Authorization result = service.authorize(command("key-1", "card-123", "200000.00"));

        assertThat(result.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(result.declineReason())
                .contains(AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED);
        verify(cardRepository, never()).findById(any());
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
        verify(authorizationRepository).update(result);
        verify(expiryJobScheduler, never()).schedule(any());
    }

    @Test
    // 测试目的：验证未配置币种会在账户锁之前直接拒绝。
    // variant：USD 不在 AuthorizationPolicyProperties，避免后续业务对象被无意义加载。
    void declinesUnsupportedCurrencyBeforeLoadingCard() {
        arrangeNewClaim();

        Authorization result = service.authorize(command(
                "key-1",
                "card-123",
                "10.00",
                Currency.getInstance("USD")
        ));

        assertThat(result.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(result.declineReason()).contains(AuthorizationDeclineReason.UNSUPPORTED_CURRENCY);
        verify(cardRepository, never()).findById(any());
    }

    @Test
    // 测试目的：验证额度不足时只 DECLINE authorization，不更新 account。
    // variant：reserved 已占用 950/1000，再请求 100，available credit 不足。
    void declinesWhenAvailableCreditIsInsufficient() {
        arrangeNewClaim();
        CreditAccount account = activeAccount("1000.00", "950.00");
        Card card = activeCard("card-123", account.id());
        when(cardRepository.findById("card-123")).thenReturn(Optional.of(card));
        when(creditAccountRepository.findByIdForUpdate(account.id()))
                .thenReturn(Optional.of(account));

        Authorization result = service.authorize(command("key-1", "card-123", "100.00"));

        assertThat(result.status()).isEqualTo(AuthorizationStatus.DECLINED);
        assertThat(result.declineReason())
                .contains(AuthorizationDeclineReason.INSUFFICIENT_AVAILABLE_CREDIT);
        assertThat(account.reservedAmount().amount()).isEqualByComparingTo("950.00");
        verify(creditAccountRepository, never()).update(any());
        verify(expiryJobScheduler, never()).schedule(any());
    }

    @Test
    // 测试目的：验证卡不存在会映射为业务拒绝原因，而不是系统异常。
    // variant：card repository 返回 empty，authorization 进入 CARD_NOT_FOUND。
    void declinesWhenCardDoesNotExist() {
        arrangeNewClaim();
        when(cardRepository.findById("unknown-card")).thenReturn(Optional.empty());

        Authorization result = service.authorize(command("key-1", "unknown-card", "100.00"));

        assertThat(result.declineReason())
                .contains(AuthorizationDeclineReason.CARD_NOT_FOUND);
    }

    @Test
    // 测试目的：验证 blocked card 在锁账户前被拒绝。
    // variant：卡状态 BLOCKED，不能继续 reserve credit。
    void declinesWhenCardIsBlocked() {
        arrangeNewClaim();
        when(cardRepository.findById("card-blocked")).thenReturn(Optional.of(new Card(
                "card-blocked",
                UUID.randomUUID(),
                CardStatus.BLOCKED
        )));

        Authorization result = service.authorize(command("key-1", "card-blocked", "100.00"));

        assertThat(result.declineReason()).contains(AuthorizationDeclineReason.CARD_BLOCKED);
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    // 测试目的：验证 expired card 在锁账户前被拒绝。
    // variant：卡状态 EXPIRED，不能继续 reserve credit。
    void declinesWhenCardIsExpired() {
        arrangeNewClaim();
        when(cardRepository.findById("card-expired")).thenReturn(Optional.of(new Card(
                "card-expired",
                UUID.randomUUID(),
                CardStatus.EXPIRED
        )));

        Authorization result = service.authorize(command("key-1", "card-expired", "100.00"));

        assertThat(result.declineReason()).contains(AuthorizationDeclineReason.CARD_EXPIRED);
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    // 测试目的：验证同 idempotency key 的相同请求直接返回第一次结果。
    // variant：claim=false 表示 duplicate loser，读取 winner 后不重复 reserve、schedule 或 publish。
    void returnsExistingIdempotentResultWithoutReservingAgain() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(authorizationRepository.claim(eq("key-1"), any())).thenReturn(false);
        when(authorizationRepository.findByIdempotencyKeyForUpdate("key-1"))
                .thenReturn(Optional.of(existing));

        Authorization result = service.authorize(command("key-1", "card-123", "100.0"));

        assertThat(result).isSameAs(existing);
        verify(cardRepository, never()).findById(any());
        verify(creditAccountRepository, never()).findByIdForUpdate(any());
        verify(authorizationRepository, never()).update(any());
        verify(expiryJobScheduler, never()).schedule(any());
        verify(eventPublisher, never()).append(any());
    }

    @Test
    // 测试目的：验证同 idempotency key 但不同请求体会触发冲突。
    // variant：金额从 100 改成 200，fingerprint 不一致，不能返回旧授权伪装成功。
    void rejectsDifferentRequestUsingSameIdempotencyKey() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(authorizationRepository.claim(eq("key-1"), any())).thenReturn(false);
        when(authorizationRepository.findByIdempotencyKeyForUpdate("key-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.authorize(command("key-1", "card-123", "200.00")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    // 测试目的：验证普通查询路径按 authorization id 读取历史结果。
    // variant：repository 命中，service.get 返回同一个 domain object。
    void getsAuthorizationById() {
        Authorization existing = approvedAuthorization("card-123", "100.00");
        when(authorizationRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        assertThat(service.get(existing.id())).isSameAs(existing);
    }

    @Test
    // 测试目的：验证普通查询路径找不到时抛出明确异常。
    // variant：repository empty，API 层可把它映射成 404。
    void throwsWhenAuthorizationDoesNotExist() {
        UUID id = UUID.fromString("8f2d8907-0471-4209-9862-73e09f62cd1f");
        when(authorizationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("authorization not found: " + id);
    }

    private void arrangeNewClaim() {
        AtomicReference<Authorization> claimed = new AtomicReference<>();
        when(authorizationRepository.claim(eq("key-1"), any())).thenAnswer(invocation -> {
            claimed.set(invocation.getArgument(1));
            return true;
        });
        when(authorizationRepository.findByIdempotencyKeyForUpdate("key-1"))
                .thenAnswer(invocation -> Optional.of(claimed.get()));
    }

    private AuthorizationCommand command(String key, String cardId, String amount) {
        return command(key, cardId, amount, Currency.getInstance("JPY"));
    }

    private AuthorizationCommand command(
            String key,
            String cardId,
            String amount,
            Currency currency
    ) {
        return new AuthorizationCommand(
                key,
                cardId,
                new BigDecimal(amount),
                currency,
                "merchant-123",
                "JP",
                "JP"
        );
    }

    private CreditAccount activeAccount(String limit, String reserved) {
        Currency currency = Currency.getInstance("JPY");
        return CreditAccount.restore(
                UUID.randomUUID(),
                new Money(new BigDecimal(limit), currency),
                new Money(new BigDecimal(reserved), currency),
                CreditAccountStatus.ACTIVE
        );
    }

    private Card activeCard(String cardId, UUID accountId) {
        return new Card(cardId, accountId, CardStatus.ACTIVE);
    }

    private Authorization approvedAuthorization(String cardId, String amount) {
        return Authorization.restore(
                UUID.randomUUID(),
                command("existing-key", cardId, amount).requestFingerprint(),
                cardId,
                new Money(new BigDecimal(amount), Currency.getInstance("JPY")),
                AuthorizationStatus.APPROVED,
                null,
                NOW,
                NOW,
                NOW.plusSeconds(7 * 24 * 60 * 60),
                null,
                null
        );
    }
}
