# Project Development Rules

## Project Context

This project is a mini credit card payment backend created to prepare for a
PayPay Card Backend Engineer interview. It should demonstrate practical backend
engineering skills, including:

- Object-oriented programming with Java
- RDBMS usage and database client implementation
- Data structures, algorithms, and object-oriented design fundamentals
- Concurrency and distributed computing awareness
- RESTful API and platform component design
- Domain-driven design
- High-traffic system design considerations
- Future event-driven architecture, Kafka, gRPC, NoSQL, and distributed cache
  experience where justified

Keep the project focused enough that its architecture, trade-offs, and important
implementation details can be clearly explained during an interview.

## Technology Stack

- Use Java 21.
- Use Spring Boot 3.x.
- Use Gradle and the Gradle Wrapper.
- Use MySQL 8.
- Redis may be introduced later when there is a concrete requirement.
- Kafka or the Outbox Pattern may be introduced later when there is a concrete
  event-driven requirement.
- Do not introduce Redis, Kafka, or the Outbox Pattern prematurely.

## Architecture Rules

Use a clear layered architecture guided by Domain-Driven Design principles.
Keep dependencies and responsibilities explicit.

- `controller` handles API requests, input validation, and API responses only.
- `service` handles application use cases, business logic orchestration, and
  transaction boundaries.
- `repository` handles database access and persistence concerns.
- `domain` and `entity` express core business concepts, rules, and state.
- `dto` contains API request and response objects.
- Do not place business logic in controllers.
- Keep domain rules close to the domain objects they protect when appropriate.
- Do not force DDD patterns where they add ceremony without improving clarity.

## Coding Rules

- Keep code simple, clear, and suitable for interview explanation.
- Prefer explicit and understandable implementations over clever abstractions.
- Do not over-engineer.
- Do not introduce complex frameworks before they solve a demonstrated need.
- Follow the existing project structure and conventions.
- Add focused tests for important business logic and behavioral changes.
- Keep changes small and scoped to the current task.
- After every modification, ensure `./gradlew test` passes.

## Financial Backend Priorities

- Design all state-changing APIs and operations with idempotency in mind.
- Make transaction boundaries explicit and explain why they are correct.
- Consider concurrent requests, race conditions, locking, and consistency.
- Model state transitions explicitly and reject invalid transitions.
- Never use `double` or `float` for monetary amounts.
- Prefer `BigDecimal` when decimal arithmetic and currency-scale semantics need
  to be explicit.
- Prefer `long` minor units when integer arithmetic, fixed currency scale, and
  simpler persistence are appropriate.
- Document and explain the monetary representation chosen for each domain model.
- Consider failure recovery and partial-failure behavior before adding
  distributed workflows.

## Current Phase Restrictions

The current phase is limited to the basic Spring Boot project and local database
connection.

- Do not implement a complete payment system.
- Do not add user login or authentication.
- Do not add Kafka.
- Do not add Redis.
- Do not add the Outbox Pattern yet.
- Do not split the project into complex microservices.
- Do not add credit card authorization, capture, or refund flows until they are
  explicitly requested.
- Do not create unrelated infrastructure or abstractions in anticipation of
  future requirements.
