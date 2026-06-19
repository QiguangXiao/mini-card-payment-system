package com.minicard.statement.domain.event;

import java.time.Instant;
import java.util.UUID;

public interface StatementDomainEvent {

    UUID statementId();

    UUID creditAccountId();

    Instant occurredAt();
}
