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
- MyBatis for primary repository implementations
- JdbcTemplate retained for one focused comparison example
- Apache Kafka with Transactional Outbox event publication
- JUnit 5 and Spring MVC Test

## Current Stage

The project currently contains a minimal runnable Spring Boot application, a
health check endpoint, and the first DDD vertical slice for card authorization
with local and simulated external risk checks.

See [Authorization Design](docs/authorization-design.md) for the aggregate,
transaction, idempotency, and concurrency decisions.
See [Kafka and Outbox Design](docs/kafka-outbox-design.md) for event delivery,
consumer idempotency, partition ordering, and failure-recovery decisions.
See [MyBatis and SQL Learning Notes](docs/mybatis-sql-learning-cn.md) for
MyBatis XML mapper usage, batching, SQL indexes, locking, transactions, and
backend interview talking points.
See [Domain State Flow Notes](docs/domain-state-flow-cn.md) for the full
authorization-to-repayment state transitions, lock ordering, row-level lock
scope, and request-by-request examples.

Most repositories use MyBatis XML mappers so SQL, pessimistic locks, and
idempotency behavior remain explicit while repetitive JDBC row mapping is
reduced. `JdbcRiskVelocityRepository` intentionally remains implemented with
`JdbcTemplate` as a small comparison example.

The `monitoring` module currently exposes a lightweight public liveness check:

```http
GET /api/health
```

```json
{"status":"OK"}
```

It intentionally does not query MySQL or Kafka. Dependency health, metrics, and
operational diagnostics belong to Spring Boot Actuator; future public
operational APIs can be added under `monitoring` when a concrete requirement
exists.

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
approved or declined by a domain policy. The current policy uses configurable
single-transaction limits and reserves available credit from a locked
`CreditAccount`. A separate `Card` model validates card lifecycle and maps cards
to accounts, allowing multiple cards to share one credit limit. The Risk module
checks local velocity, high amount, merchant, and geolocation rules before
calling a simulated external risk service protected by timeout, fallback, and a
circuit breaker. Capture, reservation release, and refund flows are not
implemented yet.

Final authorization decisions are written to a MySQL Transactional Outbox in the
same transaction as the decision. A scheduled publisher later sends them to
Kafka using at-least-once delivery.

Two independent consumer groups demonstrate different production patterns:

- The independent Notification bounded context creates idempotent `Notification`
  aggregates and owns their delivery lifecycle.
- The existing Risk bounded context maintains an idempotent, replayable
  card-risk feature projection. It is deliberately not modeled as an aggregate.

Each consumer has its own retry and dead-letter topic.

Local development includes these sample cards:

- `card-123`: active JPY account with a `100000.00` credit limit
- `card-secondary`: another active card sharing `card-123`'s account
- `card-low-limit`: active JPY account with a `5000.00` credit limit
- `card-blocked`: blocked card
- `card-expired`: expired card
- `card-account-blocked`: active card linked to a blocked account
- `card-usd`: active USD account with a `1000.00` credit limit

## Local MySQL

Start MySQL and Kafka:

```bash
docker compose up -d
```

The local database connection is:

- Database: `mini_card`
- Host: `localhost:3306`
- Application user: `root`
- Application and local root password: `rootpassword`

Local Kafka is available at `localhost:9092`. The authorization event topic is:

```text
mini-card.authorization-events.v1
```

Describe the topic or inspect events:

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
mini-card.authorization-notification.dlt.v1
mini-card.authorization-risk-feature.dlt.v1
```

Stop local services:

```bash
docker compose down
```

Named Docker volumes keep MySQL and Kafka data between restarts. To also remove
local database and Kafka data, run `docker compose down -v`.

This early project stage uses `schema.sql` instead of a migration tool. After
pulling a schema change, recreate the local development database with:

```bash
docker compose down -v
docker compose up -d
```

Do not use this reset approach for valuable or production data. Production
schema evolution requires versioned migrations.

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
