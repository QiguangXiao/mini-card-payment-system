# Kafka and Transactional Outbox Design

## Purpose

Authorization is a synchronous financial decision, but several follow-up
activities should not increase its API latency or share its transaction:

- Cardholder notification
- Risk-feature and fraud analytics updates
- Operations, audit, and reporting projections

These consumers are deliberately not implemented yet. The current scope creates
a stable event contract and a reliable publication mechanism they can use later.

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

## Event Contract

Topic:

```text
mini-card.authorization-events.v1
```

Event type:

```text
authorization.decided
```

The envelope contains:

- `eventId`: consumer idempotency key
- `eventType`: routing and observability
- `eventVersion`: schema evolution
- `occurredAt`: business event time
- `payload`: authorization decision data

The payload represents `amount` as decimal text plus `currency`. This preserves
financial precision across JSON storage and prevents consumers from accidentally
using binary floating-point arithmetic.

The Kafka key is `authorizationId`. Kafka guarantees ordering within a
partition, so later lifecycle events for the same Authorization can use the same
key and remain ordered.

## Publisher Concurrency

The publisher loads rows with:

```sql
SELECT ...
FROM outbox_events
WHERE status = 'PENDING'
ORDER BY created_at, id
LIMIT ?
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED` allows multiple application instances to poll without selecting
the same rows. After a publication failure, the current batch stops so newer
events do not overtake the failed event. Failed events use capped exponential
backoff and become `DEAD` after the configured maximum attempts.

Holding an Outbox row lock while waiting for Kafka acknowledgement is simple and
safe for this learning project. A higher-throughput production implementation
could claim rows in a short transaction, use leases, and publish concurrently
per partition key.

## Future Consumers

### Cardholder Notification

Sends push/email notifications after approval or decline. It must deduplicate by
`eventId` so retries never send duplicate customer messages.

### Risk Feature Consumer

Updates velocity counters and model features from authorization outcomes. This
is a strong future candidate for Kafka Streams, Redis, or a dedicated feature
store.

### Operations and Audit Projection

Builds query-friendly operational views without coupling the authorization
transaction to reporting workloads.

Capture, reversal, and refund should produce their own versioned event types
when those domains are implemented. They should not be inferred solely from an
authorization decision event.

## Local Versus Production Kafka

Docker Compose runs one Kafka container in combined KRaft broker/controller mode
with replication factor one. It is suitable for local learning only.

Production should normally use multiple brokers, replicated partitions,
authentication/encryption, monitoring, retention policies, consumer-lag alerts,
and an operational process for replaying dead events.
