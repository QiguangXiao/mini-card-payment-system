package com.minicard.authorization.infrastructure.mybatis;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationDeclineReason;
import com.minicard.authorization.domain.AuthorizationRepository;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.authorization.domain.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * AuthorizationRepository 的 MyBatis adapter，负责 domain object 与数据库 row 的转换。
 *
 * <p>面试重点：idempotency claim 和 SELECT ... FOR UPDATE 由数据库保证，
 * application service 只依赖 repository port，不依赖 MyBatis/JDBC 细节。</p>
 */
@Repository
public class MyBatisAuthorizationRepository implements AuthorizationRepository {

    private final AuthorizationMapper authorizationMapper;

    public MyBatisAuthorizationRepository(AuthorizationMapper authorizationMapper) {
        this.authorizationMapper = authorizationMapper;
    }

    @Override
    public Optional<Authorization> findById(UUID id) {
        return Optional.ofNullable(authorizationMapper.findById(id.toString()))
                .map(this::toDomain);
    }

    @Override
    public boolean claim(String idempotencyKey, Authorization authorization) {
        try {
            // INSERT-first idempotency claim：由 DB unique constraint 选择 winner，
            // 而不是靠内存 check，因此多线程/多实例下也成立。
            authorizationMapper.insert(idempotencyKey, toRow(authorization));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public Optional<Authorization> findByIdempotencyKeyForUpdate(String idempotencyKey) {
        // locking read 会让 duplicate request 等待 winner 完成，
        // 然后在同一个 transaction 里读取最终结果。
        return Optional.ofNullable(
                authorizationMapper.findByIdempotencyKeyForUpdate(idempotencyKey)
        ).map(this::toDomain);
    }

    @Override
    public Optional<Authorization> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(authorizationMapper.findByIdForUpdate(id.toString()))
                .map(this::toDomain);
    }

    @Override
    public void update(Authorization authorization) {
        // Mapper XML 只更新 decision columns。request identity 保持 immutable，
        // 方便 idempotency audit 和问题排查。
        authorizationMapper.update(toRow(authorization));
    }

    private AuthorizationRow toRow(Authorization authorization) {
        // Infrastructure adapter 做 mapping，避免 domain object 暴露数据库列名/字符串状态。
        return new AuthorizationRow(
                authorization.id().toString(),
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

    private Authorization toDomain(AuthorizationRow row) {
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

    private AuthorizationDeclineReason optionalDeclineReason(String value) {
        return value == null ? null : AuthorizationDeclineReason.valueOf(value);
    }
}
