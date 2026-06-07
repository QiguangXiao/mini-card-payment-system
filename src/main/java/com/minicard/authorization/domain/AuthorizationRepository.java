package com.minicard.authorization.domain;

import java.util.Optional;
import java.util.UUID;

public interface AuthorizationRepository {

    Optional<Authorization> findById(UUID id);

    Authorization saveOrGet(String idempotencyKey, Authorization authorization);
}
