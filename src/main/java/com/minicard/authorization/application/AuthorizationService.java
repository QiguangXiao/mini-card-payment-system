package com.minicard.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDecision;
import com.minicard.authorization.domain.AuthorizationDecisionPolicy;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardAuthorizationFailure;
import com.minicard.card.domain.CardAuthorizationResult;
import com.minicard.card.domain.CardRepository;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.creditaccount.domain.CreditReservationFailure;
import com.minicard.creditaccount.domain.CreditReservationResult;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.risk.domain.RiskDeclineReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授权用例的 application service，负责串起 idempotency、风控、卡状态、额度预占和事件写入。
 *
 * <p>面试重点：这里是 transaction boundary。Controller 不做业务决策，domain aggregate
 * 不直接访问数据库，service 负责把多个 aggregate 和 repository 按正确顺序组合起来。</p>
 */
@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizationRepository;
    private final AuthorizationDecisionPolicy decisionPolicy;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final RiskAssessmentService riskAssessmentService;
    private final AuthorizationDecisionEventPublisher eventPublisher;
    private final AuthorizationExpiryJobScheduler expiryJobScheduler;
    private final Clock clock;

    public AuthorizationService(
            AuthorizationRepository authorizationRepository,
            AuthorizationDecisionPolicy decisionPolicy,
            CardRepository cardRepository,
            CreditAccountRepository creditAccountRepository,
            RiskAssessmentService riskAssessmentService,
            AuthorizationDecisionEventPublisher eventPublisher,
            AuthorizationExpiryJobScheduler expiryJobScheduler,
            Clock clock
    ) {
        this.authorizationRepository = authorizationRepository;
        this.decisionPolicy = decisionPolicy;
        this.cardRepository = cardRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.riskAssessmentService = riskAssessmentService;
        this.eventPublisher = eventPublisher;
        this.expiryJobScheduler = expiryJobScheduler;
        this.clock = clock;
    }

    @Transactional
    public Authorization authorize(AuthorizationCommand command) {
        Instant now = Instant.now(clock);
        // 先创建 PENDING aggregate：每次请求都先落一条可审计(auditable)记录，
        // 再进入风控、卡状态和额度检查，符合真实发卡行 issuer flow。
        Authorization pending = Authorization.request(
                command.requestFingerprint(),
                command.cardId(),
                command.requestedAmount(),
                now
        );


        // claim() 是本事务第一笔写入：利用 idempotency_key 唯一索引让并发 retry 只有一个 winner。
        // loser 不会继续做额度预占(reserve credit)，而是在下面读取 winner 的结果。
        boolean claimed = authorizationRepository.claim(command.idempotencyKey(), pending);
        Authorization persisted = authorizationRepository
                .findByIdempotencyKeyForUpdate(command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException(
                        "authorization claim was not visible"
                ));
        // FOR UPDATE 会锁住已 claim 的 authorization row，确保并发 duplicate 等到最终状态后再返回。
        // assertSameIdempotentRequest() 校验 fingerprint，防止同一个幂等键隐藏了不同交易。
        assertSameIdempotentRequest(persisted, command);

        if (!claimed) {
            // claimed=false 表示这是 retry 或并发 duplicate；直接返回能避免 double reservation。
            return persisted;
        }

        // 只有 idempotency winner 才能进入真实决策路径(decision path)。
        decideAndReserve(persisted, command, now);
        authorizationRepository.update(persisted);
        if (persisted.status().isApproved()) {
            // DelayJob 和 APPROVED authorization 在同一事务提交：
            // 只要额度 hold 生效，就一定有一个未来释放额度的 durable plan。
            expiryJobScheduler.schedule(persisted);
        }
        // Outbox row 也在同一 MySQL transaction 写入；这里不直接发 Kafka，
        // 因为 broker/network failure 不应该破坏主交易，只需要后续可恢复发布。
        eventPublisher.append(persisted);
        return persisted;
    }

    @Transactional(readOnly = true)
    public Authorization get(UUID id) {
        // 查询用例使用 readOnly transaction，表达这里不改变业务状态，也减少误写入风险。
        return authorizationRepository.findById(id)
                .orElseThrow(() -> new AuthorizationNotFoundException(id));
    }

    private void decideAndReserve(
            Authorization authorization,
            AuthorizationCommand command,
            Instant now
    ) {
        // 便宜的 policy check 先执行，尽量推迟 account row lock，缩短高并发下的 critical section。
        AuthorizationDecision decision = decisionPolicy.decide(authorization);
        if (!decision.approved()) {
            // apply() 让 aggregate 自己维护状态转换(state transition)，service 不直接改字段。
            authorization.apply(decision, now);
            return;
        }

        // Card 是独立 aggregate：回答“这张卡能不能用”；CreditAccount 回答“额度够不够”。
        Card card = cardRepository.findById(authorization.cardId()).orElse(null);
        if (card == null) {
            authorization.decline(AuthorizationDeclineReason.CARD_NOT_FOUND, now);
            return;
        }
        CardAuthorizationResult eligibility = card.checkAuthorizationEligibility();
        if (!eligibility.eligible()) {
            // Card domain 产出自己的失败原因，再映射成 Authorization decline reason 给 API/audit 使用。
            authorization.decline(mapCardFailure(eligibility.failure()), now);
            return;
        }

        // riskAssessmentService.assess() 放在账户锁之前：风控可能有计算/外部调用成本，
        // 不应该让同账户的其他 authorization 在锁上白等。
        RiskDecision riskDecision = riskAssessmentService.assess(command.toRiskAssessmentRequest());
        if (!riskDecision.approved()) {
            authorization.decline(mapRiskFailure(riskDecision.declineReason()), now);
            return;
        }

        // findByIdForUpdate() 对 credit_accounts 做 SELECT ... FOR UPDATE。
        // 这是额度并发控制核心：同一账户的请求在这里串行检查 availableCredit 并更新 reservedAmount。
        CreditAccount account = creditAccountRepository
                .findByIdForUpdate(card.creditAccountId())
                .orElse(null);
        if (account == null) {
            authorization.decline(AuthorizationDeclineReason.CREDIT_ACCOUNT_NOT_FOUND, now);
            return;
        }

        // reserve() 是 domain behavior，不是 service 里的普通加减法。
        // CreditAccount aggregate 负责保证 reservedAmount <= creditLimit。
        CreditReservationResult reservation = account.reserve(authorization.requestedAmount());
        if (!reservation.reserved()) {
            authorization.decline(mapFailure(reservation.failure()), now);
            return;
        }

        // 同一 transaction 内先保存 account reservedAmount，再把 authorization 标成 APPROVED。
        // 任一写入失败都会 rollback，避免“授权成功但额度没冻结”的不一致状态。
        creditAccountRepository.update(account);
        authorization.approve(now);
    }

    private AuthorizationDeclineReason mapCardFailure(CardAuthorizationFailure failure) {
        return switch (failure) {
            case CARD_BLOCKED -> AuthorizationDeclineReason.CARD_BLOCKED;
            case CARD_EXPIRED -> AuthorizationDeclineReason.CARD_EXPIRED;
        };
    }

    private AuthorizationDeclineReason mapFailure(CreditReservationFailure failure) {
        return switch (failure) {
            case ACCOUNT_BLOCKED -> AuthorizationDeclineReason.CREDIT_ACCOUNT_BLOCKED;
            case CURRENCY_MISMATCH -> AuthorizationDeclineReason.CURRENCY_MISMATCH;
            case INSUFFICIENT_AVAILABLE_CREDIT ->
                    AuthorizationDeclineReason.INSUFFICIENT_AVAILABLE_CREDIT;
        };
    }

    private AuthorizationDeclineReason mapRiskFailure(RiskDeclineReason failure) {
        return switch (failure) {
            case VELOCITY_EXCEEDED -> AuthorizationDeclineReason.RISK_VELOCITY_EXCEEDED;
            case HIGH_RISK_AMOUNT -> AuthorizationDeclineReason.RISK_HIGH_AMOUNT;
            case GEOLOCATION_MISMATCH -> AuthorizationDeclineReason.RISK_GEOLOCATION_MISMATCH;
            case BLOCKED_MERCHANT -> AuthorizationDeclineReason.RISK_BLOCKED_MERCHANT;
            case EXTERNAL_RISK_DECLINED -> AuthorizationDeclineReason.RISK_EXTERNAL_DECLINED;
            case EXTERNAL_RISK_UNAVAILABLE -> AuthorizationDeclineReason.RISK_EXTERNAL_UNAVAILABLE;
        };
    }

    private void assertSameIdempotentRequest(
            Authorization existing,
            AuthorizationCommand command
    ) {
        // 这个 guard 是 idempotency 的安全边界：相同 key + 不同请求必须拒绝，
        // 否则客户端 bug 可能把一笔新交易错误地当成旧交易结果。
        if (!command.matches(existing)) {
            throw new IdempotencyConflictException();
        }
    }
}
