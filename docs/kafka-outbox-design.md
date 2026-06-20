# Kafka and Transactional Outbox Design

## Purpose

Authorization is a synchronous financial decision, but several follow-up
activities across the issuer backend should not increase API latency or share
the main money-changing transaction:

- Cardholder notification
- Risk-feature and fraud analytics updates
- Statement-ready and repayment notifications
- Operations, audit, and reporting projections

The current implementation includes Notification, Risk Feature, and Ledger
consumers with deliberately different side-effect and idempotency strategies.

## Why Transactional Outbox

Publishing directly to Kafka inside `AuthorizationService` creates a dual-write
problem. The same problem appears in posting, statement generation, repayment,
and any future money-changing workflow:

```text
MySQL commits, Kafka fails -> business state exists but event is lost
Kafka succeeds, MySQL rolls back -> consumers observe a decision that never existed
```

The application instead inserts `outbox_events` in the same MySQL transaction as
the business state change. For authorization, this means the Authorization
decision and CreditAccount reservation commit with the Outbox row:

```text
Authorization + CreditAccount + DelayJob + Outbox Event -> one MySQL commit
Outbox Publisher -> Kafka later
```

Kafka can be unavailable without losing the event intent. The publisher retries
pending rows after recovery.

## Delivery Semantics

Delivery is **at least once**.

The publisher can crash after Kafka acknowledges an event but before MySQL marks
the Outbox row as `PUBLISHED`. The event will then be published again. Kafka
producer idempotence reduces duplicates caused by producer retries, but it
cannot make the Kafka acknowledgement and MySQL commit one atomic operation.

Every consumer must therefore store and check `eventId` before applying side
effects:

```text
begin consumer database transaction
  insert eventId into consumer_inbox using a unique constraint
  apply consumer business change
commit
```

If the unique insert fails, the consumer has already processed the event and can
acknowledge it without repeating the side effect.

Kafka offset commits and MySQL transactions are also not one atomic transaction.
A consumer may commit MySQL and crash before committing its Kafka offset. Kafka
then redelivers the event, and consumer idempotency prevents the side effect from
being applied twice.

## Event Contract

Topics:

```text
mini-card.authorization-events.v1
mini-card.transaction-events.v1
mini-card.statement-events.v1
mini-card.repayment-events.v1
```

Event types:

```text
authorization.approved
authorization.declined
authorization.expired
authorization.posted
card_transaction.posted
statement.closed
repayment.received
```

The envelope contains:

- `eventId`: consumer idempotency key
- `eventType`: routing and observability
- `eventVersion`: schema evolution
- `occurredAt`: business event time
- `payload`: event-specific business data

Message payloads are plain JSON objects inside the envelope. The project
intentionally does not create one Java payload class per event type yet;
consumers use `eventType` for routing and read the fields they need from
`JsonNode payload`. This keeps the learning project focused on event contracts,
not DTO class proliferation.

The payload represents `amount` as decimal text plus `currency`. This preserves
financial precision across JSON storage and prevents consumers from accidentally
using binary floating-point arithmetic.

The Kafka key is the Outbox row's `partition_key`. Authorization events use
`authorizationId`, CardTransaction events use `cardTransactionId`, and
Statement/Repayment events use `creditAccountId`. Kafka guarantees ordering only
within one partition, so the key should represent the aggregate or account scope
where order matters.

## Publisher Concurrency

The publisher claims rows with:

```sql
SELECT ...
FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at, id
LIMIT ?
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED` allows multiple application instances to claim different rows.
The claim transaction marks rows as `PROCESSING` and sets `next_attempt_at` as a
short lease deadline. Kafka publication then happens outside the database row
lock, and a final transaction marks the row `PUBLISHED`.

If a publisher crashes after claiming, the lease expires and the stuck-event
recoverer moves the row back through the normal retry/backoff state machine.
Failed events use capped exponential backoff and become `DEAD` after the
configured maximum attempts.

## Messaging Package Boundary

The shared `messaging` package contains delivery mechanisms and integration
contracts, not business consumers:

```text
messaging
├── event       shared integration event envelope
├── inbox       shared consumer idempotency mechanism
├── inbox/mybatis
├── kafka       Kafka configuration, publisher adapter, and transport reader
├── outbox      reliable event publication mechanism
└── outbox/mybatis
```

Notification and Risk Kafka listeners remain inside their own bounded contexts.
This makes Kafka an adapter for business capabilities instead of making
`messaging` a catch-all pseudo-domain.

`event`, `kafka`, `inbox`, and `outbox` could become separate Gradle modules if
the code base or team ownership grows. Keeping them as packages is currently
easier to explain and avoids premature module boundaries.

Business-specific event translation lives in each bounded context, such as
`authorization.infrastructure.messaging`,
`transaction.infrastructure.messaging`, `statement.infrastructure.messaging`,
and `repayment.infrastructure.messaging`. The shared Outbox mechanism therefore
does not depend on business aggregates or absorb bounded-context knowledge.

Consumers use the shared `IntegrationEventReader` only for transport concerns:
JSON parsing and header validation. Notification and Risk still choose their own
handlers and commands inside their bounded contexts. This is the intended
microservice-compatible boundary: a consumer depends on the JSON event contract,
not on the producer service's internal infrastructure package.

## Implemented Consumers

### Notification Bounded Context

Consumer group:

```text
mini-card-notification-v1
```

Notification is modeled as an independent bounded context. `Notification` is an
aggregate root that owns its `PENDING -> SENT` or `PENDING -> FAILED` lifecycle,
delivery-attempt count, and transition rules. Kafka is only an inbound adapter:
the listener translates the integration event into an application command and
does not appear in the domain model.

The current use case creates durable `notifications` rows for authorization,
card transaction, statement, and repayment events. The `source_event_id` unique
constraint prevents duplicate aggregate creation when Kafka redelivers an event.

A future notification sender can independently retry provider calls and track
delivery status without blocking Kafka partitions or replaying the original
authorization event.

### Ledger Projection

Consumer group:

```text
mini-card-ledger-v1
```

Ledger is modeled as an independent bounded context for learning. Its inbound
adapters consume `card_transaction.posted` and `repayment.received`, then call
`RecordLedgerEntryService`.

The service first claims the event in `consumer_inbox`, then appends a
`ledger_entries` row in the same local transaction. The table also has a
`source_event_id + entry_type` unique constraint, so duplicate Kafka delivery or
manual replay cannot create duplicate accounting entries.

The current entries are intentionally minimal:

```text
CARD_TRANSACTION_POSTED -> DEBIT
REPAYMENT_RECEIVED -> CREDIT
```

Authorization events do not create Ledger entries because authorization is a
credit hold, not a posted receivable.

### Risk Feature Projection

Consumer group:

```text
mini-card-risk-feature-v1
```

Risk features belong to the existing Risk bounded context because they are
historical inputs for risk assessment. They are explicitly modeled as a
projection, not an aggregate root: the counters are derived from events,
eventually consistent, and can be rebuilt.

The projection service first claims the event in `consumer_inbox`, then updates
`card_risk_features` in the same MySQL transaction. If projection persistence
fails, both changes roll back and Kafka can safely redeliver.

This projection is intentionally simple. A larger production system could move
the feature computation to Kafka Streams, Redis, or a dedicated feature store.
The current hard velocity rule still queries authorization records because an
eventually consistent projection should not silently enforce a strong real-time
limit.

Notification subscribes to authorization, transaction, statement, and repayment
topics. Risk feature projection currently subscribes only to authorization
events. Kafka delivers each event once per consumer group, allowing notification
and risk processing to scale and fail independently where they share a source
topic.

## Retry and Dead Letter Topics

Each consumer has its own listener container factory and DLT:

```text
mini-card.notification.dlt.v1
mini-card.authorization-risk-feature.dlt.v1
mini-card.ledger.dlt.v1
```

Transient exceptions are retried twice with a one-second fixed backoff. Contract
errors such as malformed JSON, a mismatched `eventId` header, or an unsupported
event version are permanent and go directly to the appropriate DLT.

Separate DLTs matter because a message can be valid for one consumer while the
other consumer fails due to its own database or business rule. Operations can
inspect and replay one consumer without disturbing the other consumer group.

DLT messages retain the original payload, key, partition relationship, and
Spring Kafka exception headers. Production operations should alert on DLT
growth, inspect the root cause, deploy a fix, and replay messages deliberately
rather than automatically looping DLT messages back into the source topic.

### Ledger and Reconciliation Projections

Ledger is now implemented as a minimal learning projection. It consumes
`card_transaction.posted` and `repayment.received`, claims the event through
`consumer_inbox`, and writes append-only `ledger_entries`:

```text
card_transaction.posted -> CARD_TRANSACTION_POSTED / DEBIT
repayment.received -> REPAYMENT_RECEIVED / CREDIT
```

This is intentionally not a production double-entry general ledger. It teaches
that CardTransaction, Statement, Repayment, and Ledger answer different
questions. Reconciliation remains a likely future module that would compare
internal records against external network or bank statements.

Reversal, refund, dispute, and chargeback should produce their own versioned
event types when those domains are implemented. They should not be inferred
solely from an authorization decision event.

## Local Versus Production Kafka

Docker Compose runs one Kafka container in combined KRaft broker/controller mode
with replication factor one. It is suitable for local learning only.

Production should normally use multiple brokers, replicated partitions,
authentication/encryption, monitoring, retention policies, consumer-lag alerts,
and an operational process for replaying dead events.
