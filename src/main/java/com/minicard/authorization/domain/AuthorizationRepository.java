package com.minicard.authorization.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationRepository {

    Optional<Authorization> findById(UUID id);

    boolean claim(String idempotencyKey, Authorization pendingAuthorization);

    Optional<Authorization> findByIdempotencyKeyForUpdate(String idempotencyKey);

    Optional<Authorization> findNextExpiredApprovedForUpdate(Instant now);

    void update(Authorization authorization);
}
