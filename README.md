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
- Redis with Caffeine L1 for low-risk read model caching
- MyBatis for primary repository implementations
- JdbcTemplate retained for one focused comparison example
- Apache Kafka with Transactional Outbox event publication
- JUnit 5 and Spring MVC Test

## Current Stage

The project currently contains a runnable issuer-side credit card backend slice:
card authorization, presentment posting, statement generation, manual and
automatic repayment, notification creation, local and simulated external risk
checks, minimal Ledger projection, Kafka-based event delivery, Transactional
Outbox, Consumer Inbox, DelayJob scheduling, and Caffeine L1 + Redis L2 snapshot
caching for statement and card snapshots.

See [Core Implementation Walkthrough](docs/implementation-walkthrough-cn.md) for
the request-to-table learning path, current package map, state transitions,
ID-generation points, and Outbox/DelayJob reliability flow.
See [Spring, Java, and Library Usage Notes](docs/spring-java-technical-learning-cn.md)
for a technical walkthrough of annotations, constructor injection, validation,
configuration binding, transactions, schedulers, MyBatis, Kafka, cache,
Feign/Resilience4j, and Java language habits used in this project.
See [Authorization Design](docs/authorization-design.md) for the aggregate,
transaction, idempotency, and concurrency decisions.
See [Kafka and Outbox Design](docs/kafka-outbox-design.md) for event delivery,
consumer idempotency, partition ordering, and failure-recovery decisions.
See [Async Workflows Comparison](docs/async-workflows-comparison-cn.md) for a
side-by-side Chinese walkthrough of schedulers, Outbox, DelayJob, Kafka
producer/consumer contexts, platform execution resources, and why their names
are similar or intentionally different.
See [Statement Job Design](docs/statement-job-design-cn.md) for the flattened
sharded claimable-job design, the before/after of dropping the parent batch,
and a comparison with the DelayJob and Outbox job implementations.
See [PayPay Card Backend Interview Guide](docs/paypay-card-backend-interview-guide-cn.md)
for interview-focused key points, answer patterns, and common follow-up
questions grounded in this project.
See [PayPay Card JD Fit Guide](docs/paypay-card-jd-fit-cn.md) for a JD-to-project
evidence map, prioritized gap analysis, and answer templates tied to this
project.
See [High Traffic System Design Notes](docs/high-traffic-system-design-cn.md)
for authorization hot-path capacity analysis, bottleneck diagnosis, load-test
scenarios, rate limiting, backpressure, cache/Kafka degradation, and production
interview answers.
See [AWS ECS Deployment Notes](docs/aws-ecs-deployment-cn.md) for a
beginner-friendly but production-oriented map from this project's Docker Compose
shape to ECS/Fargate, ALB, RDS, MSK, ElastiCache, CloudWatch, CloudFormation,
CodePipeline, AWS resource naming, and small/medium/large production sizing.
See [MyBatis and SQL Learning Notes](docs/mybatis-sql-learning-cn.md) for
MyBatis XML mapper usage, batching, SQL indexes, locking, transactions, and
backend interview talking points.
See [Domain State Flow Notes](docs/domain-state-flow-cn.md) for the full
authorization-to-repayment state transitions, lock ordering, row-level lock
scope, and request-by-request examples.
See [Credit Card Lifecycle Notes](docs/credit-card-lifecycle-cn.md) for broader
issuer-side business concepts such as authorization, presentment, statement,
payment, refund, dispute, ledger, and reconciliation.
See [Snapshot Cache Design](docs/cache-snapshot-design-cn.md) for the Caffeine
L1 + Redis L2 cache design, naming choices, TTL/evict behavior, and why only
low-risk snapshots are cached.
See [Remaining Domain Roadmap](docs/ToDo.md) for the suggested learning order
for ledger, reconciliation, reversal, refund, dispute, settlement, and user/auth
topics.

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

See [JVM Core, GC, and Monitoring Notes](docs/jvm-monitoring-learning-cn.md) for
the interview-oriented explanation of JVM memory structure, request-allocation
growth, GC, threads, liveness/readiness, production troubleshooting, and why JVM
diagnostics stay outside the public business API.
See [Thread Runtime Notes](docs/thread-runtime-learning-cn.md) for the
project-specific runtime thread model covering Tomcat request threads,
schedulers, worker pools, Kafka listeners, OS thread mapping, thread states, and
production troubleshooting.
See [Local DB Schema Sync Notes](docs/db-schema-sync-2026-06-21-cn.md) for the
2026-06-21 local MySQL schema drift fix, including updated columns, indexes,
constraints, data backfill, and runtime verification.
See [Database Migration Notes](docs/database-migration-liquibase-cn.md) for the
Liquibase migration setup, local operations, and examples of outdated table
structures.

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
Statement generation snapshots posted transactions into `statement_items`, and
repayment reduces `posted_balance` while advancing statement payment status.
Minimal Ledger then consumes posted transaction and repayment events to record
append-only internal accounting entries.
The cache layer stores only low-risk snapshots: `GET /api/statements/{id}` uses
a cached statement read model, and authorization/posting/expiry use a cached
card snapshot. Caffeine handles short-lived in-process hits, Redis shares a
TTL-based L2 cache across app instances, and repayment evicts the cached
statement after commit.

A separate `Card` model validates card lifecycle and maps cards to accounts,
allowing multiple cards to share one credit limit. The Risk module checks local
velocity, high amount, merchant, and geolocation rules before calling a
simulated external risk service protected by timeout, fallback, and a circuit
breaker. Authorization reversal, refund, dispute/chargeback, production-grade
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

Local Redis is available at `localhost:6379`. It is used as the L2 cache for
statement read models and card snapshots; Caffeine remains the per-JVM L1 cache.

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
[Database Migration Notes](docs/database-migration-liquibase-cn.md).

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
