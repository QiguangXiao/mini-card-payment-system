package com.minicard.authorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
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
 * <p>关键词：授权用例, 幂等, 额度预占, authorization service,
 * idempotency, amount hold, オーソリ処理(オーソリしょり),
 * 利用可能額の確保(りようかのうがくのかくほ)。</p>
 *
 * <p>interview重点：这里是 transaction boundary。Controller 不做业务决策，domain aggregate
 * 不直接访问数据库，service 负责把多个 aggregate 和 repository 按正确顺序组合起来。</p>
 */
// @Service 让 use case 成为 Spring bean，并把它标识为 application service。
// 如果只是 new AuthorizationService(...)，@Transactional、依赖注入和统一配置都不会自动生效。
@Service
public class AuthorizationService {

    private final AuthorizationRepository authorizationRepository;
    private final Map<Currency, Money> singleTransactionLimits;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final RiskAssessmentService riskAssessmentService;
    private final AuthorizationDomainEventPublisher eventPublisher;
    private final AuthorizationExpiryJobScheduler expiryJobScheduler;
    private final Clock clock;

    public AuthorizationService(
            AuthorizationRepository authorizationRepository,
            AuthorizationPolicyProperties policyProperties,
            CardRepository cardRepository,
            CreditAccountRepository creditAccountRepository,
            RiskAssessmentService riskAssessmentService,
            AuthorizationDomainEventPublisher eventPublisher,
            AuthorizationExpiryJobScheduler expiryJobScheduler,
            Clock clock
    ) {
        // 这里刻意手写 constructor，而不是 @RequiredArgsConstructor：
        // 除了注入依赖，还要把 YAML 中的 currency string 预转换成 Currency/Money。
        // 如果每次授权时再转换，配置错误会在运行中才暴露，也会把技术解析逻辑散进热路径。
        this.authorizationRepository = authorizationRepository;
        this.singleTransactionLimits = policyProperties.singleTransactionLimits()
                .entrySet()
                .stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> Currency.getInstance(entry.getKey()),
                        entry -> new Money(entry.getValue(), Currency.getInstance(entry.getKey()))
                ));
        this.cardRepository = cardRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.riskAssessmentService = riskAssessmentService;
        this.eventPublisher = eventPublisher;
        this.expiryJobScheduler = expiryJobScheduler;
        this.clock = clock;
    }

    // @Transactional 通过 Spring proxy 生效，适合从 Controller 调用的 public use case。
    // 如果把核心写路径拆成同类 private/self-invocation 方法再加注解，事务不会按预期打开。
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
        // 如果没有这个 INSERT-first claim，同一个支付请求重试可能各自 reserve credit，造成 double hold。
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
            // 如果省掉这一步，商户不 presentment 时 reservedAmount 会长期占住额度，形成 stale hold。
            expiryJobScheduler.schedule(persisted);
        }
        // Domain event 已在 approve()/decline() 状态转换时产生。
        // Service 只负责在同一 transaction boundary 内把事实交给 Outbox adapter。
        publishDomainEvents(persisted);
        return persisted;
    }

    // readOnly=true 是给事务管理器和读者的信号：这里不应 flush 写入，也不应产生领域事件。
    // 如果查询方法和写方法都混用默认事务，review 时更难看出哪条路径会改变状态。
    @Transactional(readOnly = true)
    public Authorization get(UUID id) {
        // 查询用例使用 readOnly transaction，表达这里不改变业务状态，也减少误写入风险。
        return authorizationRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("authorization not found: " + id));
    }

    private void decideAndReserve(
            Authorization authorization,
            AuthorizationCommand command,
            Instant now
    ) {
        // 便宜的 policy check 先执行，尽量推迟 account row lock，缩短高并发下的 critical section。
        AuthorizationDeclineReason localDeclineReason = checkSingleTransactionLimit(authorization);
        if (localDeclineReason != null) {
            authorization.decline(localDeclineReason, now);
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
        // 如果先拿 account row lock 再等外部风控，慢调用会放大锁等待，热点账户容易排队超时。
        RiskDecision riskDecision = riskAssessmentService.assess(command.toRiskAssessmentRequest());
        if (!riskDecision.approved()) {
            authorization.decline(mapRiskFailure(riskDecision.declineReason()), now);
            return;
        }

        // findByIdForUpdate() 对 credit_accounts 做 SELECT ... FOR UPDATE。
        // 这是额度并发控制核心：同一账户的请求在这里串行检查 availableCredit 并更新 reservedAmount。
        // 如果只普通 SELECT，两笔并发授权可能看到同一个 availableCredit，然后都批准，造成超额授权。
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

    private AuthorizationDeclineReason checkSingleTransactionLimit(Authorization authorization) {
        Money limit = singleTransactionLimits.get(authorization.requestedAmount().currency());
        if (limit == null) {
            return AuthorizationDeclineReason.UNSUPPORTED_CURRENCY;
        }
        if (authorization.requestedAmount().isGreaterThan(limit)) {
            return AuthorizationDeclineReason.SINGLE_TRANSACTION_LIMIT_EXCEEDED;
        }
        return null;
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

    private void publishDomainEvents(Authorization authorization) {
        for (AuthorizationDomainEvent event : authorization.pullDomainEvents()) {
            // 这里仍然是同一个 DB transaction：Outbox row 和 authorization 状态一起 commit。
            // Kafka publish 由后台 outbox worker 完成，主交易不用等待 broker。
            eventPublisher.append(event);
        }
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
