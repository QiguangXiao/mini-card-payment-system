# Kafka and Transactional Outbox Design

## Purpose

Authorization is a synchronous financial decision, but several follow-up
activities should not increase its API latency or share its transaction:

- Cardholder notification
- Risk-feature and fraud analytics updates
- Operations, audit, and reporting projections

The current implementation includes two consumers with deliberately different
side-effect and idempotency strategies.

## Why Transactional Outbox

Publishing directly to Kafka inside `AuthorizationService` creates a dual-write
problem:

```text
MySQL commits, Kafka fails -> business state exists but event is lost
Kafka succeeds, MySQL rolls back -> consumers observe a decision that never existed
```

The application instead inserts `outbox_events` in the same MySQL transaction as
the Authorization decision and CreditAccount reservation:

```text
Authorization + CreditAccount + Outbox Event -> one MySQL commit
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
  insert eventId into processed_events using a unique constraint
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
- `payload`: authorization decision data

Authorization message payloads are plain JSON objects inside the envelope. The
project intentionally does not create one Java payload class per event type yet;
consumers use `eventType` for routing and read the fields they need from
`JsonNode payload`.

The payload represents `amount` as decimal text plus `currency`. This preserves
financial precision across JSON storage and prevents consumers from accidentally
using binary floating-point arithmetic.

The Kafka key is `authorizationId`. Kafka guarantees ordering within a
partition, so later lifecycle events for the same Authorization can use the same
key and remain ordered.

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
├── contract    public message payload contracts shared by producers/consumers
├── event       shared integration event envelope
├── inbox       shared consumer idempotency mechanism
├── kafka       Kafka configuration, publisher adapter, and transport reader
└── outbox      reliable event publication mechanism
```

Notification and Risk Kafka listeners remain inside their own bounded contexts.
This makes Kafka an adapter for business capabilities instead of making
`messaging` a catch-all pseudo-domain.

`event`, `kafka`, and `outbox` could become separate Gradle modules if the code
base or team ownership grows. Keeping them as packages is currently easier to
explain and avoids premature module boundaries.

Authorization-specific event translation lives in
`authorization.infrastructure.messaging`. The shared Outbox mechanism therefore
does not depend on the Authorization aggregate or absorb business-context
knowledge.

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

The current use case creates a durable `notifications` row. The
`source_event_id` unique constraint prevents duplicate aggregate creation when
Kafka redelivers an event.

A future notification sender can independently retry provider calls and track
delivery status without blocking Kafka partitions or replaying the original
authorization event.

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

Both groups subscribe to the same topic. Kafka delivers each event once per
consumer group, allowing notification and risk processing to scale and fail
independently.

## Retry and Dead Letter Topics

Each consumer has its own listener container factory and DLT:

```text
mini-card.notification.dlt.v1
mini-card.authorization-risk-feature.dlt.v1
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

### Operations and Audit Projection

This remains a likely future consumer. It would build query-friendly operational
views without coupling authorization transactions to reporting workloads.

Capture, reversal, and refund should produce their own versioned event types
when those domains are implemented. They should not be inferred solely from an
authorization decision event.

## Local Versus Production Kafka

Docker Compose runs one Kafka container in combined KRaft broker/controller mode
with replication factor one. It is suitable for local learning only.

Production should normally use multiple brokers, replicated partitions,
authentication/encryption, monitoring, retention policies, consumer-lag alerts,
and an operational process for replaying dead events.
