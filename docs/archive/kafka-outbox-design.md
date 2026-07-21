# Kafka and Transactional Outbox Design

> **Current-code alignment (2026-07):** The full body has been checked against
> the current Kafka configuration, Outbox lease model, two source-specific
> Notification listeners, and per-channel delivery workflow. Removed Ledger,
> historical-risk, statement, and repayment event paths are described only as
> historical scope decisions. See
> [events-outbox-inbox-kafka-cn.md](../events-outbox-inbox-kafka-cn.md) for the
> shorter primary reading path.

## Purpose

Authorization is a synchronous financial decision, but several follow-up
activities across the issuer backend should not increase API latency or share
the main money-changing transaction:

- Authorization-decision and posted-transaction cardholder notification
- Future operations, audit, reconciliation, or reporting consumers

The current implementation includes only the Notification consumer group. The
former Ledger, historical-risk, statement-ready, and repayment-notification
consumers were removed to keep one representative Kafka side-effect path. They
must not be presented as current features; future consumers would need their
own group identity, DLT route, Inbox identity, and replay ownership.

## Why Transactional Outbox

Publishing directly to Kafka inside `AuthorizationService` creates a dual-write
problem. The current Posting flow has the same problem because it emits
Authorization and CardTransaction events. Statement generation and Repayment
currently emit no Kafka events; the problem would apply there only if a future
workflow adds reliable publication:

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
`authorizationId`, and CardTransaction events use `cardTransactionId`. Kafka
guarantees ordering only within one partition, so the key should represent the
aggregate scope where order matters. The current Outbox publisher has four
workers, so a single scheduler thread and `ORDER BY created_at` do not guarantee
global publish order; consumers must still validate state/version when ordering
matters.

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

Notification is modeled as an independent bounded context. `Notification` is
now an immutable intent: who should be notified, about which subject, and with
which template type. Delivery lifecycle does **not** live on that aggregate.
Each `NotificationDelivery` owns its per-channel
`PENDING -> PROCESSING(lease) -> SENT / PENDING retry / DEAD` state, attempts,
provider receipt, and lease token. This allows APP_PUSH to succeed while EMAIL
is still retrying.

The two source-specific listeners translate authorization and card-transaction
contracts into `RequestNotificationCommand`. In one MySQL transaction,
`RequestNotificationService` claims
`consumer_inbox(notification-v1, eventId)`, inserts the immutable
`notifications` row, and fans out APP_PUSH and EMAIL
`notification_deliveries`. `notifications.source_event_id` and
`(notification_id, channel)` unique constraints provide second-line idempotency.

The current `NotificationDeliveryPoller/Claimer/Worker/Recoverer` already retries
provider calls outside the Kafka listener. Provider calls use a stable delivery
id as the provider idempotency key, so Kafka replay is not used as a delivery
retry mechanism and a slow provider does not block the source partition.

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
errors such as malformed JSON, a missing required envelope/payload field, or an
invalid UUID/decimal/time value are permanent and go directly to the appropriate
DLT. Headers are observability metadata and are not compared with the body;
unknown positive event versions are also not rejected yet, which is a schema
evolution gap rather than an implemented guarantee.

DLT selection follows the failing consumer group rather than the source topic.
There is currently one mapping—Notification group to Notification DLT—but the
shape is ready for future groups. An unknown group fails loudly and leaves the
source offset uncommitted instead of guessing a destination.

DLT messages retain the original payload, key, partition relationship, and
Spring Kafka exception headers. Production operations should alert on DLT
growth, inspect the root cause, deploy a fix, and replay messages deliberately
rather than automatically looping DLT messages back into the source topic.
The current repository has no DLT listener, alerting pipeline, or replay tool;
publishing the DLT record is implemented, operating it is still an explicit gap.

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
