package com.minicard.authorization.infrastructure.mybatis;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.shared.domain.Money;
import com.minicard.infrastructure.persistence.MySqlDuplicateKey;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * AuthorizationRepository 的 MyBatis adapter，负责 domain object 与数据库 row 的转换。
 *
 * <p>关键词：幂等抢占, 共享锁读取, idempotency claim, locking read,
 * 共有ロック(きょうゆうロック), 排他ロック(はいたロック)。</p>
 *
 * <p>interview重点：INSERT claim 的排他锁和 loser 共享锁读取由数据库保证，
 * application service 只依赖 repository port，不依赖 MyBatis/JDBC 细节。</p>
 */
// @Repository 语义上标识 persistence adapter，并让 Spring 做数据访问异常转换。
// 如果把 mapper 直接暴露给 service，domain layer 会被 SQL row/字符串状态污染。
@Repository
@RequiredArgsConstructor
public class MyBatisAuthorizationRepository implements AuthorizationRepository {

    private final AuthorizationMapper authorizationMapper;

    @Override
    /**
     * 普通读取 authorization，用于查询 API。
     */
    public Optional<Authorization> findById(UUID id) {
        return Optional.ofNullable(authorizationMapper.findById(id.toString()))
                .map(this::toDomain);
    }

    @Override
    /**
     * 通过 INSERT-first 抢占 idempotency key，决定本请求是否是 authorization winner。
     */
    public boolean claim(String idempotencyKey, Authorization authorization) {
        try {
            // INSERT-first idempotency claim：由 DB unique constraint 选择 winner，
            // 而不是靠内存 check，因此多线程/多实例下也成立。
            // 若同 key 的 winner 尚未提交，这个 INSERT 会先等待；并非捕获异常后才开始等 winner。
            authorizationMapper.insert(toRow(idempotencyKey, authorization));
            return true;
        } catch (DuplicateKeyException exception) {
            // 若把所有 DuplicateKeyException 都当成 loser，主键碰撞也会被伪装成正常重放。
            // 只吞本幂等约束的 MySQL 1062；其他约束/无法识别的异常保留原异常向上抛出。
            if (!MySqlDuplicateKey.isConstraintConflict(exception, "uk_authorizations_idempotency_key")) {
                throw exception;
            }
            return false;
        }
    }

    /**
     * loser 只读已有结果；共享 locking read 避免旧快照和不必要的 S -> X 锁升级。
     */
    @Override
    public Optional<Authorization> findByIdempotencyKeyForShare(String idempotencyKey) {
        return Optional.ofNullable(
                authorizationMapper.findByIdempotencyKeyForShare(idempotencyKey)
        ).map(this::toDomain);
    }

    @Override
    /**
     * 按 id 锁定 authorization row，供 posting/expiry 等状态转换使用。
     */
    public Optional<Authorization> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(authorizationMapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    /**
     * 更新 authorization 决策/生命周期字段。
     */
    public void update(Authorization authorization) {
        // Mapper XML 只更新 decision columns。request identity 保持 immutable，
        // 方便 idempotency audit 和问题排查。
        authorizationMapper.update(toRow(null, authorization));
    }

    /**
     * 将 Authorization domain object 转成数据库 row DTO。
     */
    private AuthorizationRow toRow(String idempotencyKey, Authorization authorization) {
        // Infrastructure adapter 做 mapping，避免 domain object 暴露数据库列名/字符串状态。
        return new AuthorizationRow(
                authorization.id().toString(),
                idempotencyKey,
                authorization.requestFingerprint(),
                authorization.cardId(),
                authorization.requestedAmount().amount(),
                authorization.requestedAmount().currency().getCurrencyCode(),
                authorization.status().name(),
                authorization.declineReason().map(Enum::name).orElse(null),
                authorization.createdAt(),
                authorization.decidedAt().orElse(null),
                authorization.expiresAt().orElse(null),
                authorization.postedAt().orElse(null),
                authorization.expiredAt().orElse(null)
        );
    }

    /**
     * 将数据库 row DTO 还原成带状态机校验的 Authorization。
     */
    private Authorization toDomain(AuthorizationRow row) {
        // idempotencyKey 是 API/persistence 层的请求身份；Authorization aggregate
        // 只恢复授权生命周期需要的不变量和状态，避免把重试协议混进 domain state machine。
        return Authorization.restore(
                UUID.fromString(row.id()),
                row.requestFingerprint(),
                row.cardId(),
                new Money(row.amount(), Currency.getInstance(row.currency())),
                AuthorizationStatus.valueOf(row.status()),
                optionalDeclineReason(row.declineReason()),
                row.createdAt(),
                row.decidedAt(),
                row.expiresAt(),
                row.postedAt(),
                row.expiredAt()
        );
    }

    /**
     * 将可空数据库枚举字符串转回 decline reason。
     */
    private AuthorizationDeclineReason optionalDeclineReason(String value) {
        return value == null ? null : AuthorizationDeclineReason.valueOf(value);
    }
}
