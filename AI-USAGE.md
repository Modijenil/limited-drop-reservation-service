# AI-USAGE

This document records how AI tooling was used, what was accepted/rejected, and how correctness was validated.

## Tools Used and Context Provided

- GitHub Copilot Chat (GPT-5.3-Codex)
  - Provided full assignment requirements and explicitly emphasized correctness under high concurrency and no overselling
  - Required stack constraints: Java/Spring, MySQL, Redis, RabbitMQ, Docker, offline tests
- Iterative prompting style
  - First requested architecture and decision rationale
  - Then requested implementation start
  - Then requested targeted enhancements:
    - stronger idempotency replay semantics
    - focused concurrency tests
    - submission packaging docs

## Accepted vs Rejected Suggestions

### 1) Accepted: MySQL as source-of-truth via atomic conditional update

- Suggestion:
  - Use single-statement conditional inventory updates (`available >= quantity`) to guarantee no oversell.
- Why accepted:
  - Strong correctness invariant at the authoritative datastore.
  - Minimal lock duration and race-safe behavior.
- Result:
  - Implemented reserve/confirm/release update methods in repository and exercised with race tests.

### 2) Accepted: Redis as accelerator, not authority

- Suggestion:
  - Use Redis Lua decrement as pre-check to reduce DB pressure during spikes.
- Why accepted:
  - Improves throughput while preserving correctness because DB still verifies.
- Result:
  - Implemented Redis coordination with graceful bypass on Redis failure.

### 3) Accepted: Transactional outbox for RabbitMQ publish reliability

- Suggestion:
  - Persist events to outbox in same DB transaction and publish asynchronously.
- Why accepted:
  - Avoids partial commit anomalies between business state and event emission.
- Result:
  - Implemented `outbox_events` table, enqueue during write flow, scheduled publisher.

### 4) Accepted: Idempotency response replay semantics

- Suggestion:
  - Persist exact first response body/status for idempotent endpoints and replay on retries.
- Why accepted:
  - Better client correctness under retry storms and network uncertainty.
- Result:
  - Implemented lookup and replay for `POST /holds` and `POST /holds/{id}/confirm`.

### 5) Rejected: "Redis-only inventory as primary truth"

- Suggestion:
  - Keep inventory solely in Redis for fastest race handling.
- Why rejected:
  - Violates correctness-first requirement under persistence/failover/drift concerns.
  - Requires complex durability and reconciliation to avoid oversell edge cases.
- Alternative used:
  - DB authoritative counters + Redis optimization layer.

### 6) Rejected: "TTL-only expiration"

- Suggestion:
  - Drive expiry entirely from Redis TTL notifications.
- Why rejected:
  - Notification loss and operational variance can skip expirations.
- Alternative used:
  - Authoritative DB scheduled sweep, with Redis/MQ as non-authoritative acceleration/backup.

### 7) Rejected: "Use only optimistic locking on full entities for inventory"

- Suggestion:
  - Load entity and rely only on version collisions for concurrent updates.
- Why rejected:
  - Higher contention retry churn under hot drops.
  - Less direct than SQL guard for no-oversell invariant.
- Alternative used:
  - Atomic conditional update as hard invariant, optimistic versioning for lifecycle rows.

## How Correctness Was Validated

### Static and structural checks

- Added schema constraints and unique keys:
  - non-negative unit counters
  - unique reservation by hold
  - idempotency key uniqueness

### Automated tests

- Unit tests (Mockito):
  - hold creation success/failure paths
  - confirmation behavior
  - conflict handling
- Service-level race test:
  - concurrent last-unit hold creation allows one winner only
- Repository-level race test:
  - concurrent reserve operations against one available unit result in exactly one success

### Build and execution checks

- `mvn -q -DskipTests compile`
- `mvn -q test`
- No reported workspace errors after implementation

## Confidence and Remaining Risk

High confidence in core invariant:

- Inventory cannot go below zero due to guarded SQL updates.

Residual risks to address in next iteration:

- Add MySQL Testcontainers concurrency tests for engine-specific lock behavior
- Add stronger in-progress semantics for same idempotency key concurrent requests
- Add retry/backoff and dead-letter handling detail for broker publish failures
