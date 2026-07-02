# Limited Drop Reservation Service

A correctness-first backend service for limited inventory drops (tickets, slots, product releases).

## What It Does

- Create temporary holds on drop inventory
- Confirm holds into final reservations
- Cancel active holds
- Auto-expire stale holds and return units
- Never oversell under concurrent load

## Tech Stack

- Java 21
- Spring Boot 3.3.x
- MySQL 8
- Redis 7
- RabbitMQ 3
- Flyway migrations
- JUnit 5 + Mockito + H2 test slice

## Core Correctness Strategy

### Inventory source of truth

MySQL is authoritative. Inventory transitions are guarded by atomic SQL updates:

- Reserve:
  - `available_units = available_units - q`
  - `held_units = held_units + q`
  - guarded by `available_units >= q`
- Confirm:
  - `held_units = held_units - q`
  - `confirmed_units = confirmed_units + q`
- Release (cancel/expire):
  - `available_units = available_units + q`
  - `held_units = held_units - q`

If the update affects 0 rows, the request lost the race and is rejected.

### Idempotency semantics

For `POST /holds` and `POST /holds/{id}/confirm`:

- Client sends `Idempotency-Key`
- Server binds key to endpoint + request hash
- On first success, stores exact response body + status in `idempotency_keys`
- On repeat request with same key + same payload, server replays original response
- If same key is reused with a different payload, server returns conflict

### Expiry safety

- Authoritative expiry: scheduled DB sweep for `ACTIVE` holds past `expires_at`
- Redis TTL and RabbitMQ events are acceleration/observability layers
- Even if Redis or RabbitMQ are unavailable, DB sweep keeps correctness intact

## API

### `GET /drops`
Return currently open drops.

### `GET /drops/{dropId}`
Return drop details and counters.

### `POST /holds`
Headers:
- `Idempotency-Key: <key>`

Body:
```json
{
  "dropId": 1,
  "userId": "user-123",
  "quantity": 2
}
```

Response:
- `201 Created` with hold payload
- `409 Conflict` when sold out / drop not open / idempotency mismatch
- `404 Not Found` when drop missing

### `POST /holds/{holdId}/confirm`
Headers:
- `Idempotency-Key: <key>`

Response:
- `200 OK` with reservation payload
- `409 Conflict` when hold invalid/expired/cancelled/confirmed state conflict
- `404 Not Found` when hold missing

### `DELETE /holds/{holdId}`
Response:
- `204 No Content` on success or already cancelled/expired
- `409 Conflict` for confirmed hold

## Data Model

- `drops`: inventory counters and drop lifecycle
- `holds`: temporary reservation with TTL and status
- `reservations`: confirmed ownership, unique by `hold_id`
- `idempotency_keys`: replay snapshots per key
- `outbox_events`: transactional event publication queue

## Run Locally (Docker)

```bash
docker-compose up --build
```

Services:

- App: http://localhost:8080
- RabbitMQ UI: http://localhost:15672 (guest/guest)
- MySQL: localhost:3306
- Redis: localhost:6379

## Run Tests

```bash
mvn test
```

## Important Trade-offs

- Chosen: DB atomic conditional updates as correctness anchor
  - Alternative: Redis-primary counters (faster, weaker durability guarantees)
- Chosen: DB expiry sweep as authoritative
  - Alternative: TTL-only expiry (faster, but not robust to cache/message outages)
- Chosen: Transactional outbox for event reliability
  - Alternative: direct broker publish in request transaction (risk of lost/ghost events)

## Current Scope and Next Work

Implemented:

- End-to-end hold/confirm/cancel/expire flow
- Idempotent response replay on create and confirm
- Repository and service-level race tests for last-unit contention

Recommended next:

- Add payment orchestration state machine
- Add request hash canonicalization hardening for complex payloads
- Add integration tests against MySQL Testcontainers for lock/timing fidelity
