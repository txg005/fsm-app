# Backend verification

Contract: API v1.0, frozen commit `88d8f3ed1444e4fade6ac71faa36e541b157eba1`.
Environment: Linux sandbox, JDK17, Gradle 8.14.3, PostgreSQL 15 on loopback
port 55432. PostgreSQL was installed and actually started; Docker was absent.
No Android files were modified. No merge performed.

## Commands and observed results

All Gradle commands below ran from `backend/`, with `JAVA_HOME` pointing to the
isolated downloaded JDK17. Unit/integration XML reports were counted, not inferred
from a generic build-success message.

| Command | Result |
| --- | --- |
| `gradle wrapper --gradle-version 8.14.3 --distribution-type bin` | exit 0, genuine generated wrapper jar |
| Initial `gradle compileKotlin` | exit 1, StatusPages implicit environment receiver; fixed |
| Initial restricted `sh gradlew ... test` | exit 1, ambiguous JUnit/Kotlin Test wildcard import; fixed |
| `sh gradlew --no-daemon --no-parallel --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process test` | exit 0, 5 unit tests |
| `sh gradlew --no-daemon --no-parallel --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process integrationTest` | exit 0, initial 8 PostgreSQL tests |
| `sh gradlew --no-daemon --no-parallel --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process test integrationTest installDist --console=plain` | exit 0, final 5 unit + 9 PostgreSQL tests, installDist built |
| `python3 src/test/smoke-local.py` | exit 0, actual Netty/HTTP/PostgreSQL smoke |

Final XML totals: unit tests 5, failures 0, errors 0, skipped 0; integration tests
9, failures 0, errors 0, skipped 0. `compileJava` and `compileTestJava` reported
NO-SOURCE because this project is Kotlin; neither test suite was NO-SOURCE.
Gradle forks a single-use JVM to honor the configured memory ceiling despite
`--no-daemon`; no persistent parallel daemon was intentionally started.

Shared-sandbox RAM contention interrupted earlier attempts before the exclusive
backend slot. Their absent/incomplete outputs were not counted as passing.
Final limits: 1024 MiB Gradle heap, 384 MiB metaspace, one worker, Kotlin compiler
in-process, test JVM 384 MiB, one test fork.

Integration environment variables were supplied only to the test process:
`TEST_DATABASE_URL`, `TEST_DATABASE_USER`, `TEST_DATABASE_PASSWORD`.
Each integration test creates and drops an isolated schema. The HTTP smoke
creates/drops a separate database and generates throwaway secrets in memory.
No environment values, access tokens, refresh tokens, or passwords were printed.

## Covered

- Frozen fixture DTO decoding, JPEG/multipart fixture, exact close/upload
  semantic hashes, strict JSON unknown/duplicate/missing/wrong-type rejection.
- Decimal, UUID, location/checklist validation and login limiter reset.
- Argon2id login, ASCII case-insensitive login, wrong/unknown credentials,
  expired access rejection, stable refresh, logout/sid revocation.
- Ownership isolation for order, private photo, and reference data.
- Optional photo/GPS close; revision conflict; missing photo; global result-ID
  reuse; SQL rollback of result, photo attachment, status, and receipt.
- Concurrent identical keys, conflicting bodies under the same key, different
  close keys, replay after CLOSED, cross-route key reuse.
- Upload duplicate/new operation ID returns the same serverPhotoId; STAGED
  hidden from canonical result; private JPEG content length/type/ETag.
- Injected rename-before-SQL failure leaves one adoptable orphan; retry creates
  one blob/row and one successful receipt.
- REPEATABLE READ view stays coherent while another connection commits close.
- Real HTTP smoke: health, login/refresh, snapshot/scope, duplicate JSON key,
  256 KiB limit with contract 413 body, multipart upload/replay, private binary
  integrity, close/replay/new-key CLOSED rejection, logout, and rate-limit
  Retry-After. A real Netty process restart retained the same receipt bytes,
  private JPEG, and valid database-backed session on the same persistent paths.

## Not established

- Docker image build, Compose runtime/config validation, named-volume/container
  restart, or deployed TLS/reverse-proxy behavior.
- Android integration or device behavior.
- Power-loss/fsync correctness of arbitrary disks/network mounts.
- Exhaustive fuzzing of malformed JPEG/multipart inputs, load/soak testing,
  every validation path, or every possible transaction crash point.
- Actual client disconnection precisely between SQL commit and response.
  Tests model uncertain delivery by replaying the original accepted operation;
  they do not claim network exactly-once.

No build artifacts, tooling, `.env`, real seed credentials, or database files
belong in the pull request.