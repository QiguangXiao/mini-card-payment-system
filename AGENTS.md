# Project Development Rules

## Project Context

This project is a mini credit card payment backend created for learning and for
preparing for a PayPay Card Backend Engineer interview. The goal is not only to
finish features, but also to understand and explain the business and engineering
trade-offs behind them.

The project should help the user learn:

- Credit card payment business concepts and transaction lifecycle
- Spring Boot application design and practical framework usage
- Java object-oriented programming and clean implementation habits
- System design for high-traffic financial backends
- Domain-driven design and where it is useful in real code

It should demonstrate practical backend engineering skills, including:

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
implementation details can be clearly explained during an interview. When a
PayPay Card interview might reasonably ask about a design choice, explicitly
call that out in code comments, documentation, or the walkthrough.

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
- 关键类、方法、代码路径和字段要添加中文解释性注释，并在必要处保留关键英文词汇，例如
  `idempotency`、`row lock`、`transaction boundary`、`aggregate`、`eventual consistency`。
- Comments should explain why the code is written this way, especially the
  ordering of important calls, not merely repeat what the code says.
- Prefer explicit and understandable implementations over clever abstractions.
- Do not over-engineer.
- Do not introduce complex frameworks before they solve a demonstrated need.
- Follow the existing project structure and conventions.
- Add focused tests for important business logic and behavioral changes only when necessary.
- Keep changes small and scoped to the current task.
- After every modification, ensure `./gradlew test` passes.
- When a new feature changes the main business flow, update
  `docs/implementation-walkthrough-cn.md` with a clear explanation of the
  request path, key classes, important database changes, and interview talking
  points.
- It is acceptable to change the database schema when it makes the business
  model clearer, safer, or easier to explain. Keep useful seed/test data when it
  helps the user inspect behavior locally.

## Financial Backend Priorities

- Design all state-changing APIs and operations with idempotency in mind.
- Make transaction boundaries explicit and explain why they are correct.
- Consider concurrent requests, race conditions, locking, and consistency.
- 考虑高并发和多线程
- 考虑缓存
- Model state transitions explicitly and reject invalid transitions.
- Prefer `BigDecimal` when decimal arithmetic and currency-scale semantics need
  to be explicit.
- Document and explain the monetary representation chosen for each domain model.
- Consider failure recovery and partial-failure behavior before adding
  distributed workflows.

## Interview and Learning Documentation

- Prefer explanations that connect credit-card payment business terms to the
  actual Java/Spring/MySQL implementation in this repository.
- For PayPay Card interview preparation, call out likely discussion points such
  as idempotency, concurrency control, transaction boundaries, row-level locking,
  domain modeling choices, failure recovery, and high-traffic trade-offs.
- The walkthrough document is part of the deliverable, not an afterthought.
  Update it whenever future work adds or materially changes a feature.
- If implementation instructions conflict with a clearer learning or interview
  outcome, choose the approach that best teaches the concept and explain the
  trade-off.

## Current Phase Restrictions

The project should expand phase by phase according to the user's current
request. Do not add advanced infrastructure or unrelated payment capabilities
just because they might be useful later.

- Do not add user login or authentication.
- Do not split the project into complex microservices.
- Do not add capture, refund, settlement, Redis, Kafka, Outbox, or new external
  integrations unless they are explicitly requested or already justified by the
  current feature.
- Do not create unrelated infrastructure or abstractions in anticipation of
  future requirements.
