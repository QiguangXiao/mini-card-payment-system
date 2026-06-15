package com.minicard.authorization.infrastructure.mybatis;

import java.time.Instant;
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
            // This remains an INSERT-first idempotency claim. The database
            // unique constraint, not an in-memory check, chooses one winner
            // across threads and application instances.
            authorizationMapper.insert(idempotencyKey, toRow(authorization));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public Optional<Authorization> findByIdempotencyKeyForUpdate(String idempotencyKey) {
        // The locking read blocks a duplicate request behind the winner and
        // then returns its final result under the same transaction.
        return Optional.ofNullable(
                authorizationMapper.findByIdempotencyKeyForUpdate(idempotencyKey)
        ).map(this::toDomain);
    }

    @Override
    public Optional<Authorization> findNextExpiredApprovedForUpdate(Instant now) {
        return Optional.ofNullable(authorizationMapper.findNextExpiredApprovedForUpdate(now))
                .map(this::toDomain);
    }

    @Override
    public void update(Authorization authorization) {
        // Mapper XML updates decision columns only. Request identity remains
        // immutable for idempotency auditing.
        authorizationMapper.update(toRow(authorization));
    }

    private AuthorizationRow toRow(Authorization authorization) {
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
                row.expiredAt()
        );
    }

    private AuthorizationDeclineReason optionalDeclineReason(String value) {
        return value == null ? null : AuthorizationDeclineReason.valueOf(value);
    }
}
