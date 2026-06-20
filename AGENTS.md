# Project Development Rules

## Project Purpose

This repository is a mini credit card payment backend for learning and for
preparing for a PayPay Card Backend Engineer interview. The goal is not only to
finish features, but to understand and explain the business and engineering
trade-offs behind them.

The project should teach and demonstrate:

- Credit card payment concepts and issuer-side transaction lifecycle.
- Spring Boot, Java 21, Gradle, MySQL 8, MyBatis, REST APIs, and clean OOP.
- Practical DDD, including where it helps and where it would add ceremony.
- Financial-backend concerns: idempotency, transaction boundaries, row-level
  locking, concurrency, failure recovery, async delivery, and high-traffic
  trade-offs.
- Interview-ready explanations tied to the actual classes, tables, request
  paths, and operational risks in this repository.

When a PayPay Card interview might reasonably ask about a design choice, call it
out in code comments, documentation, or the walkthrough.

## Current Stack And Phase

- Use Java 21, Spring Boot 3.x, Gradle Wrapper, MySQL 8, and MyBatis.
- Kafka, Outbox, Inbox, and DelayJob already exist in this project. Treat them
  as current reliability mechanisms, not as future TODOs.
- Do not introduce Redis, gRPC, NoSQL, new external integrations, or extra
  infrastructure unless the current feature clearly needs them.
- Do not add user login/authentication, split into microservices, or add
  unrelated payment capabilities such as capture, refund, or settlement unless
  explicitly requested or already justified by the current feature.
- It is acceptable to change database schema when it makes the business model
  clearer, safer, or easier to explain. Keep useful seed/test data when it helps
  local inspection.

## Architecture Rules

Use a clear layered architecture guided by DDD, but do not force DDD patterns
where they do not improve clarity.

- `controller` handles API requests, input validation, and API responses only.
- `dto` contains API request and response objects.
- `service` handles application use cases, orchestration, and transaction
  boundaries.
- `domain` expresses core business concepts, state transitions, and invariants.
- `repository` and `infrastructure` handle persistence, messaging, external
  clients, framework configuration, and other technical mechanisms.
- Keep domain rules close to the domain objects they protect when appropriate.
- Prefer simpler package boundaries and mechanism-oriented infrastructure when a
  DDD-style abstraction adds ceremony without making the business rule clearer.
- Keep Outbox and DelayJob conceptually separate: Outbox is reliable event
  publication; DelayJob is future business action scheduling.

## Coding Rules

- Keep code simple, explicit, and suitable for interview explanation.
- Prefer understandable implementations over clever abstractions.
- Follow existing project structure and conventions.
- Add focused tests for important business logic and behavioral changes.
- Keep changes scoped to the current task, but do not ignore obvious nearby
  defects. If current work exposes clearly unreasonable domain, infrastructure,
  mapper, or database design, fix it proactively when the fix is related and
  improves correctness, safety, or clarity.
- After code, configuration, schema, mapper XML, or behavior-affecting
  modifications, ensure `./gradlew test` passes, or clearly explain why it
  could not be run.
- Documentation-only modifications, such as Markdown docs, `README.md`, or
  repository guidance files, do not require running `./gradlew test`; mention
  that tests were skipped because the change was docs-only.
- Use Bean Validation such as `@Valid`, `@NotBlank`, and `@NotNull` at API DTO
  boundaries. Still keep domain invariants in domain factories/constructors so
  scheduler, Kafka consumer, repository restore, and test paths cannot create
  invalid business objects.

## Comments And Documentation

- 关键类、方法、代码路径和字段要添加中文解释性注释，并在必要处保留关键英文词汇，例如
  `idempotency`、`row lock`、`transaction boundary`、`aggregate`、`eventual consistency`。
- Comments should explain why the code is written this way, especially ordering,
  transaction boundaries, locking, retries, id generation, and state transitions.
  Do not merely repeat what the code already says.
- When adding a defensive check for race conditions, deadlocks, duplicate
  delivery, invalid state transitions, or runtime exceptions, add a Chinese
  explanatory comment that says what bug could happen without the check.
- When a feature changes the main business flow, update
  `docs/implementation-walkthrough-cn.md` with request path, key classes,
  important database changes, and interview talking points.
- Update `docs/credit-card-lifecycle-cn.md` when a change affects issuer-side
  lifecycle understanding, statement flow, notification flow, or payment
  business terminology.
- Prefer request-shaped examples in learning docs instead of high-level
  architecture summaries only.

## Financial Backend Priorities

- Design all state-changing APIs and operations with idempotency in mind.
- Make transaction boundaries explicit and explain why they are correct.
- Consider concurrent requests, race conditions, lock ordering, row locks, and
  consistency.
- Use defensive programming around duplicate requests, stale state, retry paths,
  scheduler leases, out-of-order events, null or blank business identifiers,
  monetary rounding/scale, and partial failures.
- Model state transitions explicitly and reject invalid transitions.
- Prefer `BigDecimal` when decimal arithmetic and currency-scale semantics need
  to be explicit.
- Document and explain the monetary representation chosen for each domain model.
- Consider caching trade-offs for high-traffic discussion, but do not add Redis
  or a cache layer before there is a concrete requirement.
- Consider failure recovery and partial-failure behavior before adding or
  changing distributed workflows.

## Learning And Interview Guidance

- Prefer explanations that connect credit-card payment business terms to the
  actual Java/Spring/MySQL/Kafka implementation in this repository.
- For PayPay Card interview preparation, call out likely discussion points such
  as idempotency, concurrency control, transaction boundaries, row-level locking,
  domain modeling choices, failure recovery, and high-traffic trade-offs.
- The walkthrough document is part of the deliverable, not an afterthought.
- If implementation instructions conflict with a clearer learning or interview
  outcome, choose the approach that best teaches the concept and explain the
  trade-off.
