package com.minicard.scheduling.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DelayJobRepository {

    boolean insertIfAbsent(DelayJob job);

    Optional<DelayJob> findNextRunnableForUpdate(Instant now);

    Optional<DelayJob> findByIdForUpdate(UUID id);

    void updateExecutionState(DelayJob job);
}
