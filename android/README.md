# FSM Android — implementation WIP, not accepted on device

Independent build root. Implements frozen API v1.0 at `88d8f3ed1444e4fade6ac71faa36e541b157eba1`; no backend draft endpoints. This PR is draft: final verification results below must not be read as device acceptance.

## Toolchain and run

Pinned: JDK17, Gradle8.13, AGP8.10.1, Kotlin/Compose plugin2.1.21, Compose BOM2025.05.01, KSP2.1.21-2.0.1, Room2.7.1, Ktor3.1.3, Coroutines1.10.2, WorkManager2.10.1, AndroidX EXIF1.4.1; minSdk26, compile/target36, build-tools36.0.0.

Install JDK17 and Android SDK (`platforms;android-36`, `build-tools;36.0.0`, `platform-tools`), accept SDK licenses, set `JAVA_HOME` and `ANDROID_HOME`. `gradlew` is explicitly a small Linux distribution launcher with the official Gradle8.13 SHA256, not a standard binary wrapper: run with `sh` (GitHub MCP does not preserve executable bits). No fake wrapper jar or APK is included. It downloads the pinned public distribution to Gradle's user cache. Alternatively use an installed Gradle8.13.

```sh
cd android
sh gradlew --no-daemon --no-parallel --max-workers=1 testDebugUnitTest lintDebug assembleDebug
sh gradlew --no-daemon --max-workers=1 :app:assembleDebugAndroidTest
# Requires an actual connected device/emulator:
sh gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Default debug API origin: `http://10.0.2.2:8080` (Android emulator → host). Override via `-PfsmApiUrl=https://your-host.example` (origin only, no `/api/v1`). Real device needs a reachable host, not `10.0.2.2`. Release rejects cleartext, debug permits local HTTP. Never put credentials in the URL/build properties. Obtain demo login/password from backend operator/env seed, not from fixtures (fixtures have intentionally unusable examples).

## Implemented vertical flow

Five primary routes: login; Room-backed list; card; inspection form; photos/close. Technical Room logs/outbox and explicit conflict comparison are auxiliary routes. Form edits are persisted asynchronously in order; UI indicates saving; last uncommitted keystrokes are not guaranteed after a crash. ViewModel preserves in-flight form state on rotation, SavedStateHandle preserves route/selection, Room persists saved data across processes. Scroll/IME-aware layouts; no fixed orientation.

Seven FK-related domain Room tables (employees, sites, equipment, work_orders, checklist_items, inspection_results, photos), plus outbox/app_logs/server_shadow/sync_state. Local draft create/read/update/delete has no HTTP. Photo Picker and delegated camera/FileProvider copy/re-encode bounded JPEG into account filesDir, stripping EXIF. Photo metadata stores localPath separately from protected server refs. Late import validates that its draft still exists and is editable. Delete only commits metadata in a transaction, then removes owned files.

Close validates required checklist/decimal ranges/comment, requests a fresh best-effort fix in visible UI (15s, denied/disabled/timeout/unavailable allowed), then atomically freezes result + upload dependencies + close UUID operations. No zero-coordinate fallback. QUEUED cannot be edited/deleted. Close wire bytes are durably frozen after upload receipts and before first close HTTP. Retries preserve operation ID/body; upload ACK never removes localPath. Snapshot does not overwrite local work.

User-initiated `dataSync` foreground Service sends real uploads/close requests on IO, has notification/progress/cancel, START_NOT_STICKY, bounded timeout and Android onTimeout handling. Service/one-time network-constrained Worker use one process Mutex. Worker never acts for an inactive account. RUNNING recovers after restart. Durable retry/backoff/jitter and Retry-After; 401 refresh once, auth pause; 409 retains local and canonical copies. Explicit correction only after definitive rejection, not uncertain timeout. Force-stop/background deadlines/immortality are NOT promised. On force-stop open the app again; pending remains.

Access token only memory; refresh ciphertext AES-GCM with non-exportable Android Keystore key. Per-employee Room/files/logs; backup/device-transfer excluded. Logout warns and preserves work; offline logout cannot guarantee server-side revocation. No passwords are persisted. Safe logger accepts only enum templates, writes Logcat+Room, bounds logs to 1000/7d transactionally, never raw exceptions/DTOs/paths/coordinates/comments/tokens.

## Demo (manual acceptance still required)

1. Start backend from its own README; login, load snapshot, open seeded assigned work order.
2. Start inspection, check required items and valid reading, edit comment. Delete draft to demonstrate CRUD, then recreate.
3. Turn off network; add optional camera/picker photo; close. Deny location to exercise valid MISSING. Observe QUEUED rather than CLOSED.
4. Kill/reopen process offline: Room form/photo/outbox remain. Restore network, press Service send, press Home, return: watch Room status and notification. Cancel/retry without altering frozen request.
5. After server ACK see CLOSED and retained photo localPath. Repeat sync: no second close. Separately test lost response after server commit, expired auth, changed server revision, two accounts.
6. Rotate all five screens with keyboard and while capture/upload. Exercise notifications denied, location approximate/disabled, FGS timeout and Settings force-stop; reopen to resume.

## Verification status

Environment initially supplied Java25 and no Gradle/SDK/device. Isolated JDK17/Gradle8.13/SDK36 installed under `/data/fsm-android-work/tools` (not committed). Earlier unbounded/concurrent attempts were interrupted after sandbox resource pressure; they are not counted as successful checks. Bounded verification uses one Gradle process, one worker, in-process Kotlin compiler, heap1024m/metaspace512m/ActiveProcessorCount2.

Initial WIP source verification: `testDebugUnitTest lintDebug assembleDebug :app:assembleDebugAndroidTest --stacktrace` completed with **exit0**, **BUILD SUCCESSFUL in56s**, in `/data/fsm-android-work/bounded-verify-final-512.log`. NUL-comment validation, explicit cancel/Worker handling, account photo orphan sweep and AndroidX EXIF orientation/mirroring correction are included. **8 JVM tests, zero failures/errors/skips**; **lint0errors / 19warnings**. Debug and instrumentation test APKs built. Remaining lint warnings are dependency update suggestions, missing launcher icon, KTX suggestions, and an Intent/stopService SAM diagnostic (Intent is not a listener registration).

Verification history is preserved locally: bounded attempt1 failed due to a missing test import (fixed); attempt2 and3 passed; platform EXIF was then replaced with pinned AndroidX EXIF1.4.1 per lint recommendation. The first aggregate build after this replacement reached instrumentation Kotlin compilation but hit **384MiB JVM Metaspace exhaustion**. A single sequential rerun with **512MiB Metaspace** passed. Earlier interrupted/resource-pressure attempts are not passed checks.

P1 follow-up verification: the same bounded aggregate command completed **exit0 / BUILD SUCCESSFUL in5m50s** (`/data/fsm-android-work/bounded-p1-verify.log`, 83 tasks:29 executed/54 up-to-date). **13 JVM tests, zero failures/errors/skips; lint0errors / 19warnings; debug APK and instrumentation APK built.** No schema/API version change. Room schema identity is unchanged.

Conflict state now commits result + affected outbox/dependencies and clears old server shadow in ONE transaction before any suspend canonical GET; restart also repairs the earlier WIP's QUEUED+terminal-CONFLICT combination. Every explicit resolution performs a fresh authorized GET, validates identity/account/availability/status, and compares the entire canonical DTO with the displayed choice. A changed version is shown for a new explicit confirmation; failed network/auth/404 fetches leave local result/frozen operations intact. Server CLOSED cannot be returned to draft. Both accept-server and return-to-draft use the same freshness guard.

Malformed success JSON or untrusted mutation receipts are UNKNOWN_OUTCOME, not ACK or permanent local-integrity failure. Durable bounded RETRY_WAIT keeps the same UUID, frozen payload and exact close wire bytes. Cancellation/recovered RUNNING also retain retryable frozen operations; no new idempotency key or draft is created. Missing/corrupt photo files are detected before mutation dispatch and marked definitive local integrity errors.

Executed JVM checks include frozen fixture DTO parsing/unknown fields, MockEngine exact close bytes/key replay, upload fixture SHA/semantic hash and multipart replay, one refresh on401 and auth failure without hot loop, protected contentPath guard, decimal/required-field/NUL validation, retry states/dependencies, fresh canonical policy, malformed close JSON/invalid receipt then valid replay with unchanged key/bytes, cancellation after server acceptance, invalid upload receipt, and predispatch photo integrity. **Fourteen Room/media/engine instrumentation tests compile ONLY**: original CRUD/lock/rollback/restart/dependencies/log/media checks plus conflict cancellation/restart, failed authorized resolution preserving stale-local data, changed CLOSED canonical/reconfirmation, atomic conflict rollback/legacy repair, durable unknown response replay/backoff/ACK, dispatch cancellation and missing-photo/no-HTTP. **None have executed:** `adb devices` returned no devices and `/dev/kvm` is absent. Test APK compilation is not device acceptance.

Reproduce bounded verification:
```sh
sh gradlew --no-daemon --no-parallel --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug :app:assembleDebugAndroidTest
```

Artifacts (local only, not committed): `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`; reports: `app/build/reports/tests/testDebugUnitTest/`, `app/build/reports/lint-results-debug.html`.

## Known remaining risks / unverified

- No physical/emulator camera/GPS/permissions/landscape/FGS/Keystore test and no end-to-end real-backend run yet. Test APK compilation is not execution.
- Room schema is version1; no upgrade migration is claimed. Instrumentation tests must run before acceptance.
- Filesystem and Room are not one atomic transaction. Account photo-directory orphan sweep runs on repository observation under the same process-wide media Mutex as import/download; only app-generated UUID jpg/tmp/download files absent from Room are removed. Registered queued/acknowledged photos are never swept. Camera scratch files are removed on result/cancel; a crash without callback can leave a private scratch file (no automatic scratch expiry yet).
- Server photos from another client are marked unavailable offline until explicit authenticated download; thumbnails are local. EXIF orientation/mirroring is applied before JPEG re-encode/stripping, but real-device camera/provider format coverage is not verified.
- UI is intentionally minimal, no merge editor/push/background GPS/create-work-order API. Device process-stop scenarios, concurrent account switching and explicit conflict resolution need acceptance testing. The three initial QA P1 paths (stale canonical choice, split conflict transition, malformed-success permanent failure) have targeted code/regression fixes; Room/process-death behavior remains compiled-only and must be exercised on-device before acceptance. No claim of full MVP acceptance is made.
