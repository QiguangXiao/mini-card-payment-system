package com.minicard.transaction.application;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import com.minicard.authorization.application.AuthorizationDomainEventPublisher;
import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.shared.domain.Money;
import com.minicard.authorization.domain.event.AuthorizationDomainEvent;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.transaction.domain.CardTransaction;
import com.minicard.transaction.domain.CardTransactionRepository;
import com.minicard.transaction.domain.CardTransactionStatus;
import com.minicard.transaction.domain.event.CardTransactionDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuer 侧 presentment posting use case。
 *
 * <p>关键词：入账用例, presentment, 交易入账, posting service,
 * presentment posting, posted balance, 売上処理(うりあげしょり),
 * 取引(とりひき)。</p>
 *
 * <p>Presentment 是外部网络/商户正式请款；posting 是发卡行把交易入账到持卡人账户。
 * 这里刻意不叫 capture/settlement：capture 偏商户侧，settlement 偏资金清算，不等于持卡人账户入账。</p>
 *
 * <p>流程总览（mini trace，全部在一个 DB transaction 内；锁顺序固定 authorization -&gt; account -&gt; tx claim）：</p>
 * <pre>
 * POST /api/presentments
 *  -&gt; SELECT authorization FOR UPDATE（挡住过期释放/重复请款并发改状态）
 *  -&gt; load card（不锁，只为拿 creditAccountId）
 *  -&gt; SELECT card_transaction by network_transaction_id FOR UPDATE
 *     -&gt; 已存在: POSTED 返回幂等结果 / 处理中则拒绝
 *  -&gt; 校验 authorization APPROVED、未过期、金额全等（当前只支持 full presentment）
 *  -&gt; SELECT credit_account FOR UPDATE（与 statement batch 同锁顺序，防死锁）
 *  -&gt; INSERT-first claim PENDING card_transaction
 *     -&gt; claim 失败: 重新 FOR UPDATE 读并发 winner，按已存在路径处理
 *  -&gt; account.postAuthorized: reservedAmount -&gt; postedBalance
 *  -&gt; authorization APPROVED -&gt; POSTED；transaction PENDING -&gt; POSTED
 *  -&gt; append Outbox events（authorization.posted + card_transaction.posted）
 *  -&gt; COMMIT
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class PostingService {

    private final CardTransactionRepository transactionRepository;
    private final AuthorizationRepository authorizationRepository;
    private final CardRepository cardRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final AuthorizationDomainEventPublisher authorizationEventPublisher;
    private final CardTransactionDomainEventPublisher transactionEventPublisher;
    private final Clock clock;

    /**
     * 处理 presentment 入账：幂等 claim、释放 authorization hold、增加 posted balance、写交易事件。
     */
    @Transactional
    public CardTransaction post(PostPresentmentCommand command) {
        Instant now = Instant.now(clock);
        // 阶段 1：先锁 authorization row。presentment 必须基于一笔仍可入账的 APPROVED authorization。
        // FOR UPDATE 让过期释放、重复 presentment、人工修正等路径不会同时改同一笔授权状态。
        Authorization authorization = authorizationRepository
                .findByIdForUpdate(command.authorizationId())
                .orElseThrow(() -> new NoSuchElementException(
                        "authorization not found: " + command.authorizationId()
                ));

        // 阶段 2：读取 Card，拿到 creditAccountId。Card 本身不在这里改状态，所以不需要 row lock。
        Card card = cardRepository.findById(authorization.cardId())
                .orElseThrow(() -> new PresentmentRejectedException(
                        "posted authorization references missing card " + authorization.cardId()
                ));

        // 阶段 3：用 networkTransactionId 查重。它是 presentment 的幂等键。
        // 如果已存在，后续不再锁 account，也不再移动余额，避免同一请款重复入账。
        Optional<CardTransaction> existing = transactionRepository
                .findByNetworkTransactionIdForUpdate(command.networkTransactionId());
        if (existing.isPresent()) {
            // 快路径：presentment 已存在。FOR UPDATE 会阻塞到 in-flight 入账事务提交，
            // 让这次重试直接读到最终 POSTED 结果，而不是误报 in-progress。
            return resolveExistingPresentment(command, existing.get());
        }

        // 阶段 4：校验 authorization 状态/金额/过期时间，然后锁 credit account。
        // 锁顺序固定为 authorization -> account -> transaction claim，和 statement batch 保持一致，降低死锁风险。
        validateAuthorizationCanBePosted(authorization, command.money(), now);
        CreditAccount account = creditAccountRepository.findByIdForUpdate(card.creditAccountId())
                .orElseThrow(() -> new PresentmentRejectedException(
                        "approved authorization references missing credit account "
                                + card.creditAccountId()
                ));

        // 阶段 5：创建 PENDING CardTransaction，并尝试 INSERT-first claim。
        // pending 先代表"这笔网络请款正在处理"，claim 成功后才会在同事务内推进为 POSTED。
        CardTransaction pending = CardTransaction.pending(
                command.networkTransactionId(),
                authorization.id(),
                authorization.cardId(),
                card.creditAccountId(),
                command.money(),
                now
        );

        // networkTransactionId 是 presentment 的天然 idempotency key。
        // 新 presentment 必须在 credit account row lock 之后再 INSERT claim：
        // StatementGenerationService 也先锁 account，再锁待出账交易，统一锁顺序可以避免 posting/statement 死锁，
        // 并保证账单生成期间不会漏掉正在入账的交易。
        // 如果 posting 先锁/插交易再锁 account，而 statement 先锁 account 再锁交易，高并发时会形成环路等待。
        if (!transactionRepository.claim(pending)) {
            // claim 失败说明在 [首次读, INSERT] 窗口内有并发请求抢先插入了同一 networkTransactionId。
            // 重新 FOR UPDATE 读赢家行：阻塞到对方事务提交，再判定 POSTED / in-progress。
            CardTransaction winner = transactionRepository
                    .findByNetworkTransactionIdForUpdate(command.networkTransactionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "presentment claim lost but row not visible"
                    ));
            return resolveExistingPresentment(command, winner);
        }
        // claim 成功：pending 行由本事务独占持有，直接用内存里的 aggregate，
        // 省掉一次多余的 SELECT FOR UPDATE 和对自己刚插入行恒为真的同一性校验。

        // 阶段 6：同一事务内移动余额并推进三个状态。
        // 同一个 transaction 内完成三件事：
        // 1. reservedAmount -> postedBalance，2. Authorization APPROVED -> POSTED，
        // 3. CardTransaction PENDING -> POSTED。任一步失败都会 rollback。
        // 如果拆成多个事务，中途失败会留下“授权已 posted 但账户余额没变”或“余额已变但没有交易流水”的断裂状态。
        account.postAuthorized(command.money());
        authorization.post(now);
        pending.markPosted(now);

        creditAccountRepository.update(account);
        authorizationRepository.update(authorization);
        transactionRepository.update(pending);
        // 阶段 7：发布两个不同语义的 domain events 到 Outbox。
        // 两个 aggregate 都发生了状态转换，但语义不同：
        // Authorization event 表达授权生命周期结束；CardTransaction event 表达用户交易已入账。
        // 两类 Outbox rows 仍和本次 posting transaction 一起提交，避免消息与状态不一致。
        publishAuthorizationEvents(authorization);
        publishCardTransactionEvents(pending);
        return pending;
    }

    /**
     * 处理已存在的同 networkTransactionId presentment：要么返回幂等的 POSTED 结果，
     * 要么说明它仍在并发入账中（或遗留 orphan pending）而拒绝本次重复处理。
     *
     * <p>事务归属：由 {@link #post(PostPresentmentCommand)} 在同一个 {@code @Transactional}
     * 边界内调用；existing row 已经通过 {@code FOR UPDATE} 锁住，所以这里可以安全判断最终状态。</p>
     */
    private CardTransaction resolveExistingPresentment(
            PostPresentmentCommand command,
            CardTransaction existing
    ) {
        // 同一 networkTransactionId 必须指向同一笔授权和金额；否则是外部 id 复用错误。
        assertSamePresentment(command, existing);
        if (existing.status() == CardTransactionStatus.POSTED) {
            // duplicate presentment 已入账：返回最终结果，绝不能再次 reserved -> postedBalance。
            // 没有这层 idempotency，网络重放同一 presentment 会把同一笔消费入账两次。
            return existing;
        }
        throw new PresentmentRejectedException(
                "presentment is already being processed: " + command.networkTransactionId()
        );
    }

    /**
     * 校验 authorization 是否仍可被 presentment 入账。
     *
     * <p>事务归属：由 {@link #post(PostPresentmentCommand)} 在锁住 authorization row 后调用；
     * 它本身只做校验，但校验结果决定同一事务后续是否更新 account、authorization 和 transaction。</p>
     */
    private void validateAuthorizationCanBePosted(
            Authorization authorization,
            Money presentmentAmount,
            Instant postingTime
    ) {
        if (authorization.status() != AuthorizationStatus.APPROVED) {
            throw new PresentmentRejectedException(
                    "authorization must be APPROVED before posting: " + authorization.status()
            );
        }
        Instant expiresAt = authorization.expiresAt()
                .orElseThrow(() -> new PresentmentRejectedException(
                        "approved authorization is missing expiresAt"
                ));
        if (postingTime.isAfter(expiresAt)) {
            throw new PresentmentRejectedException(
                    "authorization expired before presentment posting"
            );
        }
        if (!authorization.requestedAmount().equals(presentmentAmount)) {
            // 当前阶段只支持 full presentment。partial presentment 会引入 remaining hold，
            // 适合作为后续扩展，不在本阶段提前复杂化。
            throw new PresentmentRejectedException(
                    "presentment amount must equal authorization amount"
            );
        }
    }

    /**
     * 校验同一 networkTransactionId 的重复请求是否代表同一笔 presentment。
     *
     * <p>事务归属：当前由 {@link #resolveExistingPresentment(PostPresentmentCommand,
     * CardTransaction)} 在 posting 写事务中调用；它本身不读写数据库。</p>
     */
    private void assertSamePresentment(
            PostPresentmentCommand command,
            CardTransaction transaction
    ) {
        if (!command.matches(transaction)) {
            throw new PresentmentConflictException();
        }
    }

    /**
     * 追加 authorization.posted 事件到 Outbox。
     *
     * <p>事务归属：只由 {@link #post(PostPresentmentCommand)} 调用，加入同一个
     * {@code @Transactional} 边界；Outbox row 必须和 authorization POSTED 状态一起提交。</p>
     */
    private void publishAuthorizationEvents(Authorization authorization) {
        for (AuthorizationDomainEvent event : authorization.pullDomainEvents()) {
            authorizationEventPublisher.append(event);
        }
    }

    /**
     * 追加 card_transaction.posted 事件到 Outbox。
     *
     * <p>事务归属：只由 {@link #post(PostPresentmentCommand)} 调用，加入同一个
     * {@code @Transactional} 边界；Outbox row 必须和交易 POSTED 状态一起提交。</p>
     */
    private void publishCardTransactionEvents(CardTransaction transaction) {
        for (CardTransactionDomainEvent event : transaction.pullDomainEvents()) {
            transactionEventPublisher.append(event);
        }
    }
}
