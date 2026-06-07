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

The project currently contains only a minimal runnable Spring Boot application and
a health check endpoint:

```http
GET /api/health
```

```json
{"status":"OK"}
```

Credit card authorization, capture, refund, persistence, and concurrency controls
have not been implemented yet.

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
