# FSM backend — API v1.0

Independent Kotlin/Ktor Gradle root; JDK 17, PostgreSQL, private filesystem photo volume.
Frozen contract: `88d8f3ed1444e4fade6ac71faa36e541b157eba1`.
No Android code, draft endpoints, registration, or create-work-order endpoint.

## Verification status

The current backend, including the QA P1 comment-Unicode fix, compiles on JDK17.
The latest forced sequential Gradle rerun passed `test`, `integrationTest`, and
`installDist`: **9 unit + 11 real PostgreSQL tests, 0 failures/errors/skips**.
The new unit and HTTP/PostgreSQL NUL regressions were executed, not skipped.
Updated real Netty HTTP smoke also passed, including escaped-NUL rejection and
actual Ktor 3.1.3 multipart bytes. See `MULTIPART-INCIDENT.md` for the reproduced
photo-upload defect, fix, Windows server update, and existing-APK recovery.
See `VERIFICATION.md` for exact commands, earlier failed attempts, and limitations.
This is backend verification, not full product acceptance: the original local run did not test Docker or Android
end-to-end. Current [main CI run39741466813](https://github.com/txg005/fsm-app/actions/runs/39741466813) also passed9unit+11PG/installDist. The user separately reported Windows wrapper/backend test+installDist, Compose and emulator photo/GPS/CLOSED success; these are user reports, not a Docker/Windows automated matrix. See [ACCEPTANCE](../docs/ACCEPTANCE.md).

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

From `backend/`, with JDK 17:

```sh
sh gradlew --no-daemon --no-parallel --max-workers=1 test
sh gradlew --no-daemon --no-parallel --max-workers=1 installDist
build/install/fsm-backend/bin/fsm-backend
```

The launcher bootstraps the genuine Gradle8.14.3 wrapper JAR from its pinned official HTTPS source beside `gradle/wrapper/gradle-wrapper.properties`, verifies SHA-256 before installation and each launcher run, then runs `GradleWrapperMain`. Distribution checksum is pinned too. Use `sh gradlew` on Linux (curl/sha256sum/mktemp/tr); Windows uses `gradlew.bat`/PowerShell5.1. The historical `.base64` file is no longer used. See [Windows/bootstrap guide](../docs/BUILD-WINDOWS.md); Docker uses its pinned Gradle image, not the host launcher.

For Compose, from the repository root:

```sh
# First setup only: never overwrite an existing .env (including symlinks).
if [ -e .env ] || [ -L .env ]; then
  printf '%s\n' '.env already exists; keep it unchanged. Stop here and review it privately.' >&2
  exit 1
fi
# Noclobber also refuses a file created after the check above.
(set -C; cat .env.example > .env) || exit 1
# Edit .env privately; replace every placeholder. Never commit it.
docker compose config --quiet
docker compose up --build -d
curl --fail http://127.0.0.1:8080/health
docker compose down
```

Do not print `docker compose config` without `--quiet`: interpolation includes
secrets. PostgreSQL is not published to the host. API binds loopback only.
`down` preserves both named volumes; `down -v` irreversibly deletes demo data.
Outside local development terminate TLS at a correctly configured reverse
proxy, never expose the debug HTTP port directly to an untrusted network.

Real PostgreSQL integration tests use a new disposable schema per test:

```sh
# Export TEST_DATABASE_URL, TEST_DATABASE_USER, TEST_DATABASE_PASSWORD privately.
# The role needs CREATE SCHEMA. Do not target a production database.
sh gradlew --no-daemon --no-parallel --max-workers=1 integrationTest
```

Missing test database configuration fails the suite, rather than reporting
silently skipped integration tests. `test` runs unit fixtures only;
`integrationTest` runs the `postgres`-tagged suite separately.

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

## Remaining verification

User-reported Windows Compose/emulator operation is recorded in ACCEPTANCE.
Formal named-volume/container restart durability, deployment TLS/reverse-proxy
behavior, the full Android end-to-end matrix, exhaustive adversarial JPEG/multipart
fuzzing and abrupt power-loss durability remain unverified.
The PostgreSQL tests include fault injection after rename and before receipt;
these are simulations, not proof of physical disk power-loss behavior.

The optional Linux sandbox smoke script is intentionally environment-specific:
after `installDist`, `python3 src/test/smoke-local.py` expects local PG trust
authentication at `127.0.0.1:55432`, role `vercel-sandbox`, and `createdb`/`dropdb`.
It generates credentials in memory, creates/drops a disposable database, starts
real Netty on loopback port 58080, and prints only scenario names.
