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
- JUnit 5 and Spring MVC Test

## Current Stage

The project currently contains a minimal runnable Spring Boot application, a
health check endpoint, and the first DDD vertical slice for card authorization.

See [Authorization Design](docs/authorization-design.md) for the aggregate,
transaction, idempotency, and concurrency decisions.

Health check:

```http
GET /api/health
```

```json
{"status":"OK"}
```

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
  "currency": "JPY"
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
single-transaction limits; account limits, external risk decisions, capture,
and refund flows are not implemented yet.

## Local MySQL

Start MySQL:

```bash
docker compose up -d
```

The local database connection is:

- Database: `mini_card`
- Host: `localhost:3306`
- Application user: `root`
- Application password: `password`
- Local administrative root password: `rootpassword`

Stop MySQL:

```bash
docker compose down
```

The named Docker volume keeps MySQL data between restarts. To also remove local
database data, run `docker compose down -v`.

This early project stage uses `schema.sql` instead of a migration tool. After
pulling a schema change, recreate the local development database with:

```bash
docker compose down -v
docker compose up -d
```

Do not use this reset approach for valuable or production data. Production
schema evolution requires versioned migrations.

## Run Application

Ensure JDK 21 is installed and MySQL is healthy, then run:

```bash
./gradlew bootRun
```

## Run Tests

Ensure JDK 21 is installed, then run:

```bash
./gradlew test
```
