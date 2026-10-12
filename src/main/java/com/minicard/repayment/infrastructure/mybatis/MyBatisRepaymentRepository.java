package com.minicard.repayment.infrastructure.mybatis;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.infrastructure.persistence.MySqlDuplicateKey;
import com.minicard.repayment.domain.Repayment;
import com.minicard.repayment.domain.RepaymentRepository;
import com.minicard.repayment.domain.RepaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * RepaymentRepository 的 MyBatis adapter。
 *
 * <p>关键词：还款持久化, 幂等抢占, idempotency claim, shared locking read,
 * 入金永続化(にゅうきんえいぞくか), 共有ロック(きょうゆうロック)。</p>
 *
 * <p>repayments.idempotency_key 唯一索引用于 INSERT-first claim；duplicate 只用 FOR SHARE 读结果。
 * account/statement 必须先锁再 INSERT，以免 FK 检查的隐式 S 锁反转 application 的锁顺序。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisRepaymentRepository implements RepaymentRepository {

    private final RepaymentMapper mapper;

    @Override
    /**
     * 通过 INSERT-first 抢占 idempotency key，决定本请求是否是 repayment winner。
     */
    public boolean claim(Repayment pendingRepayment) {
        try {
            // INSERT-first claim 依赖 repayments.idempotency_key 唯一索引。
            // 如果先查再插，两个并发还款请求可能同时认为自己是第一笔。
            mapper.insert(toRow(pendingRepayment));
            return true;
        } catch (DuplicateKeyException exception) {
            // PRIMARY 冲突不能当成正常重放，否则会隐藏写库失败；只吞本还款幂等唯一约束。
            if (!MySqlDuplicateKey.isConstraintConflict(exception, "uk_repayments_idempotency_key")) {
                throw exception;
            }
            return false;
        }
    }

    @Override
    /**
     * loser 共享锁读取最新结果，不修改 repayment，也不从已有 S 锁升级到 X。
     */
    public Optional<Repayment> findByIdempotencyKeyForShare(String idempotencyKey) {
        return Optional.ofNullable(mapper.findByIdempotencyKeyForShare(idempotencyKey))
                .map(this::toDomain);
    }

    @Override
    /**
     * 普通读取 repayment，用于查询 API。
     */
    public Optional<Repayment> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString()))
                .map(this::toDomain);
    }

    @Override
    /**
     * 更新 repayment 入账状态字段。
     */
    public void update(Repayment repayment) {
        // 只推进 status/creditAccountId/receivedAt/updatedAt；request identity 保持 immutable。
        mapper.update(toRow(repayment));
    }

    /**
     * 将 Repayment domain object 转成数据库 row DTO。
     */
    private RepaymentRow toRow(Repayment repayment) {
        return new RepaymentRow(
                repayment.id().toString(),
                repayment.idempotencyKey(),
                repayment.requestFingerprint(),
                repayment.statementId().toString(),
                repayment.creditAccountId().map(UUID::toString).orElse(null),
                repayment.amount().amount(),
                repayment.amount().currency().getCurrencyCode(),
                repayment.status().name(),
                repayment.receivedAt().orElse(null),
                repayment.createdAt(),
                repayment.updatedAt()
        );
    }

    /**
     * 将数据库 row DTO 还原成带状态机校验的 Repayment。
     */
    private Repayment toDomain(RepaymentRow row) {
        return Repayment.restore(
                UUID.fromString(row.id()),
                row.idempotencyKey(),
                row.requestFingerprint(),
                UUID.fromString(row.statementId()),
                optionalUuid(row.creditAccountId()),
                new Money(row.amount(), Currency.getInstance(row.currency())),
                RepaymentStatus.valueOf(row.status()),
                row.receivedAt(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    /**
     * 将可空数据库 UUID 字符串转回 domain 可空字段。
     */
    private UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
