# FSM backend — API v1.0

Independent Kotlin/Ktor Gradle root; JDK 17, PostgreSQL, private filesystem photo volume.
Frozen contract: `88d8f3ed1444e4fade6ac71faa36e541b157eba1`.
No Android code, draft endpoints, registration, or create-work-order endpoint.

## Verification status

Implementation is **not yet accepted**. Initial compilation found a Kotlin receiver
error in `Application.kt` (fixed locally). Subsequent compilation/tests were
interrupted by shared sandbox resource failure. Do not infer passing compilation,
PostgreSQL integration, Docker build, or container durability from this document.
Docker was unavailable in the implementation sandbox.

## Configuration

Set environment privately; never commit `.env` or paste credentials into logs.

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | JDBC PostgreSQL URL |
| `DATABASE_USER` | Database role |
| `DATABASE_PASSWORD` | Database password |
| `JWT_SECRET_BASE64` | Base64 encoding of at least 32 cryptographically random bytes |
| `PHOTO_STORAGE_DIR` | Writable private filesystem directory on persistent volume |
| `PORT` | Optional listen port, default 8080 |
| `SEED_DEMO` | `true` to provision two engineers and assigned orders |
| `DEMO_LOGIN_1`, `DEMO_LOGIN_2` | Distinct ASCII logins, case insensitive |
| `DEMO_PASSWORD_1`, `DEMO_PASSWORD_2` | Private passwords, 12–128 characters |

Seed is insert-once. It never replaces existing passwords on restart. Use a
disposable database for demonstrations/tests, not production data. Employee/order
IDs are stable v4 UUIDs aligned with the contract fixtures. A second engineer
allows ownership checks. Existing credentials cannot be changed through API v1.

## Build/run

From `backend/`, with JDK 17 and Gradle 8.14.3:

```sh
gradle test
gradle installDist
build/install/fsm-backend/bin/fsm-backend
```

Startup runs Flyway migrations before accepting HTTP. Supply configuration above.
The process deliberately emits only `STARTUP_FAILED` on startup failure rather
than exposing database credentials or exception text. Inspect configuration and
database connectivity locally without publishing secrets.

`GET /health` probes PostgreSQL. API base is `/api/v1`; deployed access must use
HTTPS via a trusted reverse proxy. No forwarded-IP plugin is installed: login
rate limiting uses the TCP peer address (a proxy therefore shares one bucket).
Bind local development to loopback; Android emulator uses `10.0.2.2:8080`.

## Persistence and guarantees

Seven domain tables are separate from credentials, refresh sessions, receipts,
and Flyway history. Reads are scoped to assigned engineer; snapshot is a single
REPEATABLE READ transaction. Access JWTs include a session ID checked against
the database on every protected request. Refresh tokens are SHA-256 hashes in
PostgreSQL, remain stable across refresh, and logout revokes only that session.
Passwords are Argon2id hashes, never returned in DTOs.

Close takes an order row lock and commits result, photo attachment, status,
revision, and successful receipt together. Idempotency is shared across upload
and close by `(employeeId, operationId)` with transaction advisory locks and a
database primary key. Historical replies store exact status/body. Current
assignment is rechecked before replay, including after CLOSED.

JPEG storage uses generated UUID/hash keys, temp file, file fsync, atomic rename,
and directory fsync before SQL commit. A failure between rename and SQL commit
can leave an orphan; a retry checks the deterministic filename and hash and
adopts it. Filesystem and PostgreSQL are **not** one transaction. No automatic
STAGED/blob/receipt deletion is performed. Do not manually delete a photo volume
while retaining its database. Directory fsync/atomic rename require a supporting
local filesystem, not an arbitrary network mount.

No raw HTTP bodies, headers, passwords, tokens, GPS, comments, or raw exception
messages are logged. `X-Request-Id` is server generated.

## Required remaining verification

Run compilation/unit fixtures and real PostgreSQL integration tests sequentially
in a healthy environment. Verify malformed JSON and multipart limits, auth expiry,
ownership, replay after CLOSED, concurrent same/different keys, transaction
rollback, orphan recovery, and response-loss recovery. Docker build/Compose
health checks, separate-volume restart durability, and Android end-to-end remain
unverified until explicitly recorded with commands and exit codes.