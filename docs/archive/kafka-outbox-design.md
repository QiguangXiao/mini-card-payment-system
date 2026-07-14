# Kafka and Transactional Outbox Design

> **Archive note (2026-07)**: this document has been aligned with the slimmed architecture — sections describing the removed ledger projection, historical risk projection, and `statement.closed` / `repayment.received` event paths were taken out. The current Kafka surface is `authorization-events` + `transaction-events`, consumed by the single notification group. See [events-outbox-inbox-kafka-cn.md](../events-outbox-inbox-kafka-cn.md) for the current design.

## Purpose

Authorization is a synchronous financial decision, but several follow-up
activities across the issuer backend should not increase API latency or share
the main money-changing transaction:

- Cardholder notification
- Risk-feature and fraud analytics updates
- Statement-ready and repayment notifications
- Operations, audit, and reporting projections

The current implementation includes the Notification consumer; the other
activities above remain motivation for the event contract rather than
implemented consumers.

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
```

Event types:

```text
authorization.approved
authorization.declined
authorization.expired
authorization.posted
card_transaction.posted
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

Notification Kafka listeners remain inside their own bounded
context. This makes Kafka an adapter for business capabilities instead of
making `messaging` a catch-all pseudo-domain.

`event`, `kafka`, `inbox`, and `outbox` could become separate Gradle modules if
the code base or team ownership grows. Keeping them as packages is currently
easier to explain and avoids premature module boundaries.

Business-specific event translation lives in each bounded context, such as
`authorization.infrastructure.messaging` and
`transaction.infrastructure.messaging`. The shared Outbox mechanism therefore
does not depend on business aggregates or absorb bounded-context knowledge.

Consumers use the shared `IntegrationEventReader` only for transport concerns:
JSON parsing and header validation. Notification still chooses
its own handlers and commands inside its bounded context. This is the
intended microservice-compatible boundary: a consumer depends on the JSON event
contract, not on the producer service's internal infrastructure package.

The root `com.minicard.infrastructure` package has a different meaning from
`messaging`. It provides platform wiring such as scheduler thread pools, worker
executors, transaction helpers, cache infrastructure, and web error handling.
Outbox remains under `messaging/outbox` because it owns a reliability state
machine: `PENDING -> PROCESSING lease -> PUBLISHED/PENDING retry/DEAD`. DelayJob
stays as its own root mechanism because it represents future business actions,
not message publication.

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

The current use case creates durable `notifications` rows for authorization
and card transaction events. The `source_event_id` unique
constraint prevents duplicate aggregate creation when Kafka redelivers an event.

A future notification sender can independently retry provider calls and track
delivery status without blocking Kafka partitions or replaying the original
authorization event.

Notification subscribes to the authorization and transaction topics. Kafka
delivers each event once per consumer group, so an additional consumer group
added later would scale and fail independently while sharing the same source
topics.

## Retry and Dead Letter Topics

Each consumer group has its own DLT:

```text
mini-card.notification.dlt.v1
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

### Future Projections

Reconciliation remains a likely future module that would compare internal
records against external network or bank statements.

Reversal, refund, dispute, and chargeback should produce their own versioned
event types when those domains are implemented. They should not be inferred
solely from an authorization decision event.

## Local Versus Production Kafka

Docker Compose runs one Kafka container in combined KRaft broker/controller mode
with replication factor one. It is suitable for local learning only.

Production should normally use multiple brokers, replicated partitions,
authentication/encryption, monitoring, retention policies, consumer-lag alerts,
and an operational process for replaying dead events.
