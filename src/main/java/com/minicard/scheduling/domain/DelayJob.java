package com.minicard.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable plan for executing a business action after a delay.
 *
 * <p>This is intentionally similar to OutboxEvent: both are database-backed
 * work queues claimed by polling schedulers. The meaning is different though:
 * Outbox publishes messages, while DelayJob executes future business actions.</p>
 */
public final class DelayJob {

    private static final int MAX_ERROR_LENGTH = 500;
    private static final long MAX_RETRY_DELAY_SECONDS = 60;

    private final UUID id;
    private final DelayJobType jobType;
    private final String aggregateType;
    private final String aggregateId;
    private DelayJobStatus status;
    private int attempts;
    private final Instant scheduledAt;
    private Instant nextAttemptAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private String lastError;

    private DelayJob(
            UUID id,
            DelayJobType jobType,
            String aggregateType,
            String aggregateId,
            DelayJobStatus status,
            int attempts,
            Instant scheduledAt,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            String lastError
    ) {
        this.id = Objects.requireNonNull(id);
        this.jobType = Objects.requireNonNull(jobType);
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = requireText(aggregateId, "aggregateId");
        this.status = Objects.requireNonNull(status);
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        this.attempts = attempts;
        this.scheduledAt = Objects.requireNonNull(scheduledAt);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.lastError = lastError;
    }

    public static DelayJob pending(
            UUID id,
            DelayJobType jobType,
            String aggregateType,
            String aggregateId,
            Instant scheduledAt,
            Instant createdAt
    ) {
        return new DelayJob(
                id,
                jobType,
                aggregateType,
                aggregateId,
                DelayJobStatus.PENDING,
                0,
                scheduledAt,
                scheduledAt,
                createdAt,
                createdAt,
                null
        );
    }

    public static DelayJob restore(
            UUID id,
            DelayJobType jobType,
            String aggregateType,
            String aggregateId,
            DelayJobStatus status,
            int attempts,
            Instant scheduledAt,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            String lastError
    ) {
        return new DelayJob(
                id,
                jobType,
                aggregateType,
                aggregateId,
                status,
                attempts,
                scheduledAt,
                nextAttemptAt,
                createdAt,
                updatedAt,
                lastError
        );
    }

    public void markDone(Instant doneAt) {
        status = DelayJobStatus.DONE;
        updatedAt = Objects.requireNonNull(doneAt);
        lastError = null;
    }

    public void markProcessing(Instant startedAt, long processingTimeoutSeconds) {
        Instant actualStartedAt = Objects.requireNonNull(startedAt);
        if (processingTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("processingTimeoutSeconds must be positive");
        }
        // PROCESSING is a lease, not a permanent state. If a pod dies after
        // claiming the job, nextAttemptAt lets another pod reclaim it later.
        status = DelayJobStatus.PROCESSING;
        updatedAt = actualStartedAt;
        nextAttemptAt = actualStartedAt.plusSeconds(processingTimeoutSeconds);
    }

    public void markFailed(String error, Instant failedAt, int maxAttempts) {
        attempts++;
        updatedAt = Objects.requireNonNull(failedAt);
        lastError = truncate(requireText(error, "error"));
        if (attempts >= maxAttempts) {
            // DEAD jobs stop automatic retries. Operations can inspect the last
            // error and decide whether a manual repair or replay is appropriate.
            status = DelayJobStatus.DEAD;
            nextAttemptAt = failedAt;
            return;
        }

        status = DelayJobStatus.PENDING;
        // Keep the retry policy symmetric with Outbox: short exponential
        // backoff, capped to avoid leaving recoverable jobs dormant for hours.
        long delaySeconds = Math.min(
                1L << Math.min(attempts - 1, 6),
                MAX_RETRY_DELAY_SECONDS
        );
        nextAttemptAt = failedAt.plusSeconds(delaySeconds);
    }

    private String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public DelayJobType jobType() {
        return jobType;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public DelayJobStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant scheduledAt() {
        return scheduledAt;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String lastError() {
        return lastError;
    }
}
