# Authorization Design

## Purpose

The Authorization module demonstrates a small but explicit DDD vertical slice.
It models an issuer-side decision about whether a requested card transaction is
approved or declined. Approved requests reserve available credit on a
`CreditAccount`. It now includes a small Risk bounded-context collaboration,
but it does not yet implement capture and refund flows.

## Aggregate Boundary

`Authorization` is the aggregate root. External code cannot assign its status
directly. A new aggregate starts as `PENDING`, and only its `approve`, `decline`,
or `apply` behavior can produce a final decision.

The aggregate protects these invariants:

- A new authorization starts in `PENDING`.
- Only a pending authorization can be approved or declined.
- An approved authorization has a decision time and no decline reason.
- A declined authorization has both a decision time and decline reason.
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

Authorization records intentionally keep the presented `card_id` without a
foreign key to `cards`. This allows the system to retain a declined attempt for
an unknown card while still enforcing the Card-to-CreditAccount relationship
inside the `cards` table.

Authorization records intentionally do not have a foreign key to `cards`.
Declined attempts for unknown card identifiers still need an audit record. In a
production system, `cardId` should be an internal token and never a plaintext
primary account number.

The account protects these invariants:

- Reserved amount cannot be negative or exceed the credit limit.
- Credit limit and reserved amount use the same currency.
- Blocked accounts cannot reserve credit.
- A reservation cannot exceed currently available credit.

Available credit is calculated as:

```text
credit limit - reserved amount
```

The repository loads the account with `SELECT ... FOR UPDATE`. Concurrent
authorizations for the same account are therefore serialized before checking
and changing available credit, preventing overspending.

## Domain Service

`AuthorizationDecisionPolicy` is a domain service because the decision rule does
not naturally belong to the state of one Authorization entity. The current
`SingleTransactionLimitPolicy` approves supported currencies within configured
limits and declines other requests with an explicit reason.

The configured limits are intentionally a demonstration rule. A production card
issuer would normally coordinate with card, account, available-credit, fraud,
and risk bounded contexts.

## Risk Checks

`RiskAssessmentService` coordinates two risk layers:

- `LocalRiskPolicy` performs deterministic rules inside the service boundary:
  blocked merchant, recent authorization velocity, high amount per currency,
  and country mismatch.
- `SimulatedExternalRiskGateway` models a third-party or separate internal risk
  engine and is protected by Resilience4j timeout, circuit breaker, and
  fallback.

The current fallback is fail-closed: if external risk is unavailable, the
authorization is declined with `RISK_EXTERNAL_UNAVAILABLE`. That is conservative
for a learning project. Real issuers may choose a mixed policy, such as
fail-open only for low-value trusted merchants, but that must be explicit and
auditable.

Risk runs after Card eligibility and before the `CreditAccount` row lock. This
keeps external latency out of the account-lock critical section while still
avoiding unnecessary risk calls for missing, blocked, or expired cards.

## Application Service and Transaction

`AuthorizationService` is responsible for the use case and transaction boundary:

1. Atomically claim an idempotency key with a pending Authorization.
2. Return the original result when another request already owns that key.
3. Ask the domain policy for preliminary rules such as transaction limits.
4. Validate the Card and resolve its CreditAccount.
5. Run local and external risk checks.
6. Lock the CreditAccount and attempt to reserve available credit.
7. Approve or decline the Authorization with an explicit reason.
8. Persist the account reservation and authorization decision in one transaction.

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

All authorization flows acquire locks in the same order:

```text
Authorization idempotency row -> Card read -> CreditAccount row
```

Consistent lock ordering reduces deadlock risk as more workflows are added.

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

The CreditAccount table additionally prevents negative reservations and
reservations above the credit limit.

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

`JdbcRiskVelocityRepository` intentionally remains a small JdbcTemplate example
for comparing direct JDBC-style mapping with MyBatis. Both approaches keep SQL
explicit; MyBatis is used where repeated mappings and multiple operations make
the additional mapper structure worthwhile.

`schema.sql` is currently used only for this early local-development stage.
A production system should replace it with versioned schema migrations before
multiple environments or valuable data are introduced.

## Deliberately Deferred Production Concerns

- Production fraud/risk model integration
- Authorization expiry and reversal
- Reservation release after decline, reversal, expiry, or capture
- Optimistic versioning for later aggregate updates
- Capture and refund aggregates
- Outbox events and asynchronous processing
- Versioned database migrations
