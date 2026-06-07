# Authorization Design

## Purpose

The Authorization module demonstrates a small but explicit DDD vertical slice.
It models an issuer-side decision about whether a requested card transaction is
approved or declined. It does not yet reserve credit, call a risk system, or
implement capture and refund flows.

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

## Domain Service

`AuthorizationDecisionPolicy` is a domain service because the decision rule does
not naturally belong to the state of one Authorization entity. The current
`SingleTransactionLimitPolicy` approves supported currencies within configured
limits and declines other requests with an explicit reason.

The configured limits are intentionally a demonstration rule. A production card
issuer would normally coordinate with card, account, available-credit, fraud,
and risk bounded contexts.

## Application Service and Transaction

`AuthorizationService` is responsible for the use case and transaction boundary:

1. Create a pending Authorization.
2. Ask the domain policy for a decision.
3. Apply the decision through aggregate behavior.
4. Persist the result using an idempotency key.
5. Return the winning result for concurrent duplicate requests.

The application service coordinates the workflow but does not contain the
authorization state-transition rules.

## Idempotency and Concurrency

The idempotency key is request-processing metadata, so it is not a property of
the Authorization aggregate. The repository stores it with the row to provide
an atomic database uniqueness boundary.

MySQL chooses one winner through a unique constraint and atomic upsert.
`SELECT ... FOR UPDATE` then performs a current read, which avoids stale
snapshot behavior under MySQL's default `REPEATABLE READ` isolation.

Repeating the same key and request returns the original result. Reusing the key
for different card, amount, or currency data returns `409 Conflict`.

## API Semantics

An approved or declined authorization returns HTTP `200`. A decline is a valid
business decision, not a technical failure. Invalid requests, idempotency
conflicts, and unavailable infrastructure use HTTP error responses.

## Persistence Defense

Domain code is the primary place for business rules. The MySQL table also uses
constraints for positive amounts and valid status/decision column combinations.
This protects data when writes occur outside the normal application path.

`schema.sql` is currently used only for this early local-development stage.
A production system should replace it with versioned schema migrations before
multiple environments or valuable data are introduced.

## Deliberately Deferred Production Concerns

- Card lifecycle and blocked-card checks
- Available-credit reservation and release
- Fraud and risk service integration
- Authorization expiry and reversal
- Optimistic versioning for later aggregate updates
- Capture and refund aggregates
- Outbox events and asynchronous processing
- Versioned database migrations
