# Mini Card Payment System

## Project Goal

This project is a small credit card backend system for practicing and demonstrating
Java backend engineering and financial transaction system design for a PayPay Card
Backend Engineer interview.

## Tech Stack

- Java 21
- Spring Boot 3.x
- Gradle with Gradle Wrapper
- MySQL 8.4 LTS with Docker Compose
- Redis for risk velocity sliding-window counting and statement GET L2 cache
- Caffeine L1 for statement GET read-model cache
- MyBatis for primary repository implementations
- JdbcTemplate retained for one focused comparison example
- Apache Kafka with Transactional Outbox event publication
- JUnit 5 and Spring MVC Test

## Current Stage

The project currently contains a runnable issuer-side credit card backend slice:
card authorization, presentment posting, statement generation, manual and
automatic repayment, notification creation, local and simulated external risk
checks with Redis velocity counting, minimal Ledger projection, Kafka-based event
delivery, Transactional Outbox, Consumer Inbox, DelayJob scheduling, and
Caffeine L1 + Redis L2 cache-aside for statement GET read models.

See [Core Implementation Walkthrough](docs/implementation-walkthrough-cn.md) for
the request-to-table learning path, current package map, state transitions,
ID-generation points, the authorization/posting/statement/repayment flows, and
the authorization & credit-account aggregate design decisions (boundary,
invariants, idempotency/concurrency, persistence defense — merged from the former
authorization-design note, now archived under docs/archive/).
See [Spring, Java, and Library Usage Notes](docs/spring-java-technical-learning-cn.md)
for a technical walkthrough of annotations, constructor injection, validation,
configuration binding, transactions, schedulers, MyBatis, Kafka, cache,
Feign/Resilience4j, and Java language habits used in this project.
See [Events, Outbox, Inbox & Kafka](docs/events-outbox-inbox-kafka-cn.md) for the
transactional outbox (dual-write, claim/publish/finalize/recover), at-least-once
delivery, consumer double-idempotency (Inbox + business key), the Kafka config
reference, partition ordering, per-context DLTs, the real gaps (retention, DEAD
observability, version negotiation), and hardcore interview Q&A. (Merged from the
former kafka-outbox-design, kafka-learning, and event-outbox-messaging-design
notes, now archived under docs/archive/.)
See [Claimable Jobs (DelayJob / Outbox / StatementJob)](docs/claimable-jobs-cn.md)
for the three database-backed job families: the shared claim-lease-recover model
and seven invariants, the per-family details (4-class framework vs 1-class
dispatcher, single vs multi-column lease, backoff vs none, sharded fan-out), the
master comparison table, the StatementJob flatten (parent-batch removal) with its
data model and per-account fault isolation, the scheduler-vs-worker pool platform
resources, the real gaps (DEAD observability, completed-row retention), and Q&A.
(Merged from the former async-workflows-comparison, statement-job-design, and
claimable-job-families notes — and corrects the stale "statement batch is not a
claimable job" description — now archived under docs/archive/.)
See [PayPay Card Backend Interview Guide](docs/paypay-card-backend-interview-guide-cn.md)
for interview-focused key points, answer patterns, and common follow-up
questions grounded in this project.
See [PayPay Card JD Fit Guide](docs/paypay-card-jd-fit-cn.md) for a JD-to-project
evidence map, prioritized gap analysis, and answer templates tied to this
project.
See [PayPay Card JD Alignment Review](docs/paypay-card-jd-alignment-review-cn.md)
for an opinionated, independent assessment: what is already strong, what is
over-invested, what to strengthen now versus defer, and an interview-prep plan
for the work that lives outside this project.
See [High Traffic System Design Notes](docs/high-traffic-system-design-cn.md)
for authorization hot-path capacity analysis, bottleneck diagnosis, load-test
scenarios, rate limiting, backpressure, cache/Kafka degradation, and production
interview answers.
See [Production Runtime Sizing Notes](docs/production-runtime-sizing-cn.md) for
Tomcat thread, Hikari pool, JVM heap/GC, worker pool, Kafka concurrency, and
small/medium/large production configuration trade-offs.
See [AWS ECS Deployment Notes](docs/aws-ecs-deployment-cn.md) for a
beginner-friendly but production-oriented map from this project's Docker Compose
shape to ECS/Fargate, ALB, RDS, MSK, ElastiCache, CloudWatch, CloudFormation,
CodePipeline, AWS resource naming, and small/medium/large production sizing.
See [MyBatis, SQL & Migration Notes](docs/mybatis-sql-and-migration-cn.md) for
MyBatis XML mapper usage, batching, SQL indexes, locking, transactions, and the
Liquibase schema-migration workflow (the current 0001-0010 changesets, drift
fixes, and data backfill). (Merged from the former mybatis-sql-learning and
database-migration-liquibase notes, now archived under docs/archive/.)
See [Domain State Flow Notes](docs/domain-state-flow-cn.md) for the full
authorization-to-repayment state transitions, lock ordering, row-level lock
scope, and request-by-request examples.
See [Credit Card Domain Notes](docs/credit-card-domain-cn.md) for the issuer-side
business flow (authorization, presentment/posting, statement, repayment), the
branch flows (reversal, expiry, refund, clearing adjustment, dispute), the
ledger-vs-transaction-vs-reconciliation distinction, the issuer engineering
concerns, and the remaining-domain roadmap (what to build next and why). (Merged
from the former credit-card-lifecycle and ToDo notes, now archived under
docs/archive/.)
See [Caching & Rate Limiting](docs/caching-and-rate-limiting-cn.md) for the
statement GET two-level cache (Caffeine L1 + Redis L2, cache-aside, versioned
Lua CAS + tombstone, cross-pod rebuild lock, Pub/Sub L1 invalidation), the
velocity sliding-window limiter, why card snapshot cache was removed,
rate-limiting algorithms, Lua atomicity, Redisson build-vs-buy, general cache
design rules (penetration/breakdown/avalanche), and hardcore interview Q&A.
(Merged from the former cache-snapshot-design, cache-invalidation-broadcast, and
distributed-cache notes, now archived under docs/archive/.)

Most repositories use MyBatis XML mappers so SQL, pessimistic locks, and
idempotency behavior remain explicit while repetitive JDBC row mapping is
reduced. `JdbcRiskVelocityCounter` intentionally remains implemented with
`JdbcTemplate` behind a small `RiskVelocityCounter` port as a comparison example.

The `monitoring` module exposes a lightweight public liveness check:

```http
GET /api/health
```

```json
{"status":"OK"}
```

It intentionally does not query MySQL or Kafka. JVM runtime data, dependency
health, metrics, and operational diagnostics belong to Spring Boot Actuator:

```http
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/info
GET /actuator/metrics
GET /actuator/metrics/jvm.memory.used
GET /actuator/metrics/jvm.gc.pause
GET /actuator/metrics/jvm.threads.live
```

See [JVM, Threads & Runtime Notes](docs/jvm-threads-runtime-cn.md) for the
interview-oriented explanation of JVM memory structure, request-allocation
growth and GC, the full thread model (Tomcat request threads, scheduler/worker
pools, Kafka listeners, OS thread mapping, thread states, MySQL row-lock vs Java
BLOCKED), liveness/readiness, the monitoring/diagnostics commands, and a
production troubleshooting runbook. (Merged from the former jvm-monitoring and
thread-runtime notes, now archived under docs/archive/.)

Create an authorization:

```http
POST /api/authorizations
Idempotency-Key: checkout-request-123
Content-Type: application/json
```

```json
{
  "cardId": "card-123",
  "amount": 100,
  "currency": "JPY",
  "merchantId": "merchant-123",
  "merchantCountry": "JP",
  "cardholderCountry": "JP"
}
```

Get an authorization:

```http
GET /api/authorizations/{id}
```

Authorization requests require an `Idempotency-Key`. Repeating the same request
with the same key returns the original result. Reusing the key with different
request data returns `409 Conflict`. A MySQL unique constraint protects this
behavior when concurrent requests use the same key.

Amounts use a `Money` value object backed by Java `BigDecimal` and MySQL
`DECIMAL(19,2)`. New authorizations start as `PENDING` and are explicitly
approved or declined by a domain policy. Approved authorizations reserve credit
from a locked `CreditAccount`. Presentment posting later moves money from
`reserved_amount` to `posted_balance` and creates a posted `CardTransaction`.
Statement generation snapshots posted transactions into `statement_lines`, and
repayment reduces `posted_balance` while advancing statement payment status.
Minimal Ledger then consumes posted transaction and repayment events to record
append-only internal accounting entries.
`GET /api/statements/{id}` uses a small statement read-model cache: Caffeine is
the per-JVM L1 and Redis is the cross-instance L2. Repayment updates the MySQL
source of truth first, then evicts the statement read cache after transaction
commit; Redis CAS/tombstone ordering uses the explicit `statements.version`
instead of deriving a cache version from `paid_amount`. Card lookup remains
direct MyBatis because card snapshot stale data can affect authorization
decisions and the real hot-path bottleneck is still the locked credit account
row. Redis is also used for the risk velocity
sliding-window counter, where every authorization request benefits from avoiding
a recent-count query on MySQL.

A separate `Card` model validates card lifecycle and maps cards to accounts,
allowing multiple cards to share one credit limit. The Risk module checks local
velocity, high amount, merchant, and geolocation rules before calling a
simulated external risk service protected by timeout, semaphore bulkhead,
fallback, and a circuit breaker. Authorization reversal, refund, dispute/chargeback, production-grade
double-entry ledger, and reconciliation flows are deliberately deferred learning
topics.

Business events are written to a MySQL Transactional Outbox in the same
transaction as the state change. A scheduled publisher later sends them to Kafka
using at-least-once delivery. Future business actions such as authorization
expiry and automatic repayment are scheduled through DelayJob, not Outbox.

Three independent consumer groups demonstrate different production patterns:

- The independent Notification bounded context creates idempotent `Notification`
  aggregates and owns their delivery lifecycle.
- The existing Risk bounded context maintains an idempotent, replayable
  card-risk feature projection. It is deliberately not modeled as an aggregate.
- The Ledger bounded context records minimal append-only accounting entries from
  posted transaction and repayment events. It is a learning projection, not a
  production-grade general ledger.

Each consumer has its own retry and dead-letter topic.

Local development includes these sample cards:

- `card-123`: active JPY account with a `100000.00` credit limit
- `card-secondary`: another active card sharing `card-123`'s account
- `card-low-limit`: active JPY account with a `5000.00` credit limit
- `card-blocked`: blocked card
- `card-expired`: expired card
- `card-account-blocked`: active card linked to a blocked account
- `card-usd`: active USD account with a `1000.00` credit limit

## Local Dependencies

Start MySQL, Kafka, and Redis:

```bash
docker compose up -d
```

The local database connection is:

- Database: `mini_card`
- Host: `localhost:3306`
- Application user: `root`
- Application and local root password: `rootpassword`

Local Kafka is available at `localhost:9092`. Current event topics are:

```text
mini-card.authorization-events.v1
mini-card.transaction-events.v1
mini-card.statement-events.v1
mini-card.repayment-events.v1
```

Local Redis is available at `localhost:6379`. The current application uses it
for the risk velocity sliding-window counter and as the L2 cache for statement
GET read models; Caffeine remains the per-JVM L1 for that statement cache.

Describe a topic or inspect events:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic mini-card.authorization-events.v1

docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic mini-card.authorization-events.v1 \
  --from-beginning \
  --property print.key=true \
  --property print.headers=true
```

Dead-letter topics:

```text
mini-card.notification.dlt.v1
mini-card.authorization-risk-feature.dlt.v1
mini-card.ledger.dlt.v1
```

Stop local services:

```bash
docker compose down
```

Named Docker volumes keep MySQL, Kafka, and Redis data between restarts. To also
remove local dependency data, run `docker compose down -v`.

Database schema is managed by Liquibase on application startup. The changelog
entry point is:

```text
src/main/resources/db/changelog/db.changelog-master.yaml
```

For normal local development, start MySQL/Kafka and then run the app; Liquibase
applies pending changesets automatically:

```bash
docker compose up -d
./gradlew bootRun
```

For details and outdated-schema examples, see
[MyBatis, SQL & Migration Notes](docs/mybatis-sql-and-migration-cn.md).

## Run Application

Ensure JDK 21 is installed and MySQL/Kafka are healthy, then run:

```bash
./gradlew bootRun
```

## Run Tests

Ensure JDK 21 is installed, then run:

```bash
./gradlew test
```
