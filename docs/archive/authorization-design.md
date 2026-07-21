# Authorization Design

> **Current-code alignment (2026-07):** This archived long-form design note has
> been checked against the current implementation, not merely prefixed with a
> historical disclaimer. Current class names, transaction ordering, Redis
> velocity behavior, Outbox/DelayJob boundaries, and deliberately removed
> projections are reflected throughout the body. The shorter current reading
> path remains [`implementation-walkthrough-cn.md`](../implementation-walkthrough-cn.md#14-授权与账户聚合设计决策合并自-authorization-design).

## Purpose

The Authorization module demonstrates a small but explicit DDD vertical slice.
It models an issuer-side decision about whether a requested card transaction is
approved or declined. Approved requests reserve available credit on a
`CreditAccount`. It includes Risk bounded-context collaboration, authorization
expiry through DelayJob, presentment posting, and reliable event publication
through Outbox. The former learning-only Ledger and historical-risk Kafka
projections were deliberately removed in the 2026-07 scope reduction; refund,
reversal, dispute, production-grade double-entry ledger, and reconciliation
remain deferred learning topics rather than current code paths.

## Aggregate Boundary

`Authorization` is the aggregate root. External code cannot assign its status
directly. A new aggregate starts as `PENDING`, and only its `approve`, `decline`,
`post`, or `expire` behavior can change the lifecycle state.

The aggregate protects these invariants:

- A new authorization starts in `PENDING`.
- Only a pending authorization can be approved or declined.
- An approved authorization has a decision time and no decline reason.
- A posted authorization must have been approved first and must be posted before
  its `expiresAt` deadline.
- A declined authorization has both a decision time and decline reason.
- An expired authorization must have been approved first and can only expire at
  or after `expiresAt`.
- A pending authorization has neither decision time nor decline reason.

`Money` is a value object. It keeps amount and currency together and rejects
invalid monetary values before they reach persistence.

## Credit Account Aggregate

`CreditAccount` is a separate aggregate root because credit limit and reserved
balance have their own lifecycle and consistency rules. It is not embedded in
Authorization, and a user/customer aggregate is deliberately not required for
the authorization decision.

## Card Aggregate

`Card` maps the presented card identifier to a CreditAccount and owns the card
lifecycle status. This separation supports multiple cards sharing one account
without duplicating or fragmenting the account's available-credit balance.

The authorization flow rejects missing, blocked, and expired cards before
locking or changing their CreditAccount.

The current `CardRepository` bean reads card reference data directly through
MyBatis. An older `CachedCardRepository` design used a low-risk-looking
`CardSnapshot` with Caffeine L1 and Redis L2, but that cache was removed because
stale card status can affect authorization approval. Even if card lookup is
cached in the future, it must never cache `CreditAccount`, available credit, or
idempotency ownership, so the financial consistency boundary remains the MySQL
transaction and `CreditAccount` row lock.

Authorization records intentionally keep the presented `card_id` without a
foreign key to `cards`. This allows the system to retain a declined attempt for
an unknown card while still enforcing the Card-to-CreditAccount relationship
inside the `cards` table. In a production system, `cardId` should be an internal
token and never a plaintext primary account number.

The account protects these invariants:

- Reserved amount and posted balance cannot be negative.
- Reserved amount plus posted balance cannot exceed the credit limit.
- Credit limit, reserved amount, posted balance, and requested authorization
  amount use the same currency.
- Blocked accounts cannot reserve credit.
- A reservation cannot exceed currently available credit.

Available credit is calculated as:

```text
credit limit - reserved amount - posted balance
```

The repository loads the account with `SELECT ... FOR UPDATE`. Concurrent
authorizations for the same account are therefore serialized before checking
and changing available credit, preventing overspending.

## Local Decision Rules

The configured single-transaction limits are intentionally kept as simple
application-service logic. They are a small local rule, so the code now uses
`AuthorizationDeclineReason` directly instead of introducing a policy interface
and decision wrapper for one implementation.

A production issuer would normally coordinate card, account, available-credit,
fraud, and risk bounded contexts. In this learning project, those heavier
collaborations stay explicit in the main request flow rather than hidden behind
extra abstractions.

## Risk Checks

`RiskAssessmentService` coordinates two risk layers:

- Local checks run directly inside `RiskAssessmentService`: blocked merchant,
  recent authorization velocity, high amount per currency, and country mismatch.
- `RiskVelocityCounter` is the application port for velocity. The default
  adapter is `RedisRiskVelocityCounter`, which atomically performs ZADD,
  window trimming, ZCARD, and EXPIRE in one Lua script. Setting
  `risk.velocity.store=jdbc` selects `JdbcRiskVelocityCounter` as a comparison
  adapter, but JDBC is not the default runtime path.
- `ExternalRiskGateway` is the application port for third-party risk scoring;
  the current adapter is `ExternalRiskGatewayAdapter`, backed by Feign and
  protected with a Resilience4j semaphore Bulkhead and CircuitBreaker fallback.

The two degradation policies are deliberately asymmetric. Redis velocity is an
auxiliary high-frequency signal and fails open with a metric when Redis is
unavailable; it does not fall back to a database COUNT because that could turn a
Redis brownout into a MySQL/Hikari brownout. External risk is treated as a final
decision dependency and fails closed with `RISK_EXTERNAL_UNAVAILABLE` when the
provider fails, the circuit is open, or the bulkhead is full. A real issuer may
choose a more nuanced low-value or trusted-merchant policy, but it must be
explicit, observable, and auditable.

Risk runs after Card eligibility and before the `CreditAccount` row lock. This
keeps external latency out of the account-lock critical section while still
avoiding unnecessary risk calls for missing, blocked, or expired cards.

## Application Service and Transaction

`AuthorizationService` is responsible for the use case and transaction boundary:

1. Build a request fingerprint and a new `PENDING` Authorization.
2. Atomically INSERT-first claim the idempotency key, then read the claimed row
   with `SELECT ... FOR UPDATE` and compare the fingerprint.
3. Return the winner's completed result for an identical duplicate; reject the
   same key with different request data as `409 Conflict`.
4. Check the configured single-transaction limit before any account lock.
5. Read the Card and reject missing, blocked, or expired cards.
6. Run Redis velocity/local rules and the external risk call before the account
   lock, so slow I/O does not expand the financial critical section.
7. Lock the CreditAccount and attempt to reserve available credit.
8. Approve or decline the Authorization with an explicit reason.
9. For an approval, persist the account reservation, authorization decision,
   expiry DelayJob, and Outbox event in one transaction; declines also append
   their decision event but do not create an expiry job.

The application service coordinates the workflow but does not contain the
authorization state-transition rules.

This monolith intentionally updates two aggregates in one local database
transaction because approving an authorization without reserving its credit
would violate a critical financial invariant. A distributed version would need
a different consistency design and explicit failure recovery.

## Idempotency and Concurrency

The idempotency key is request-processing metadata. The repository stores it
with the row to provide an atomic database uniqueness boundary. The row also
stores a SHA-256 request fingerprint so the application can reject reuse of the
same idempotency key for different card, amount, currency, merchant, or
geolocation data.

MySQL chooses one winner through the unique idempotency-key constraint. The
winner owns the pending Authorization and may reserve credit. A duplicate
request blocks behind the winner and then reads the completed result with
`SELECT ... FOR UPDATE`, avoiding duplicate reservations and stale snapshot
behavior under MySQL's default `REPEATABLE READ` isolation.

The create-authorization path acquires or reads data in this order:

```text
Authorization INSERT-first claim
-> Authorization row FOR UPDATE
-> Card read
-> risk checks (Redis + external HTTP, no account lock held)
-> CreditAccount row FOR UPDATE
```

The ordering has two purposes: the idempotency winner is decided before any
side effect, and the potentially slow risk call finishes before the account row
lock. Other money paths keep the shared financial lock order consistent:
CreditAccount precedes Statement when both are needed; Posting first locks its
Authorization, then CreditAccount, before changing balances.

Repeating the same key and request returns the original result. Reusing the key
for different request data returns `409 Conflict`.

## API Semantics

An approved or declined authorization returns HTTP `200`. A decline is a valid
business decision, not a technical failure. Invalid requests, idempotency
conflicts, and unavailable infrastructure use HTTP error responses.

## Persistence Defense

Domain code is the primary place for business rules. The MySQL table also uses
constraints for positive amounts and valid status/decision column combinations.
This protects data when writes occur outside the normal application path.

The CreditAccount table additionally prevents negative reserved or posted
amounts and prevents `reserved_amount + posted_balance` from exceeding the
credit limit.

Primary persistence adapters use MyBatis XML mappers. Domain repository
interfaces remain independent from MyBatis:

```text
domain Repository interface
        <- MyBatis Repository adapter
        <- Mapper interface + XML SQL + persistence Row
```

Persistence Row records mirror database columns. Repository adapters translate
them into domain objects through constructors and `restore` methods. This keeps
MyBatis concerns, mutable persistence conventions, and SQL mapping outside the
domain model.

`JdbcRiskVelocityCounter` remains a selectable JdbcTemplate comparison adapter
behind the `RiskVelocityCounter` port. The default Redis implementation is a
distributed sliding-window log, not a cache of an SQL result; that distinction
is worth remembering for an interview because every authorization avoids the
COUNT query instead of depending on cache hit rate.

Database schema is managed by Liquibase changelogs under
`src/main/resources/db/changelog`. This keeps local schema drift visible and
lets schema changes include historical data backfill, indexes, and constraints
instead of relying on `CREATE TABLE IF NOT EXISTS`.

## Deliberately Deferred Production Concerns

- Production fraud/risk model integration and real provider contracts.
- Authorization reversal from the network before presentment.
- Partial presentment and partial hold release.
- Refund, clearing adjustment, dispute, and chargeback flows.
- Production-grade double-entry ledger and reconciliation reports.
- A production DLT monitor and controlled replay workflow; the current Kafka
  error handler publishes poison messages to a Notification DLT, but no DLT
  consumer automatically repairs or replays them.
- Optimistic versioning for later aggregate updates.
- User/cardholder identity, authentication, authorization, and PII handling.
- Production-grade migration deployment controls such as online DDL review,
  rollout windows, and forward-fix playbooks.
