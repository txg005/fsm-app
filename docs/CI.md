# Continuous verification

## Scope and commands

`.github/workflows/verification.yml` runs on every pull request (including draft
PRs), pushes to `main`, and manual dispatch once
GitHub exposes the workflow on the default branch. No merge is required for a PR
run. There are no path filters, so every PR receives the same three checks.

All jobs use standard `ubuntu-24.04` GitHub-hosted runners and Temurin JDK17:

| Check | Actual command from its build root | Timeout |
| --- | --- | --- |
| Backend PostgreSQL | `sh gradlew --no-daemon --no-parallel --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process test integrationTest installDist --console=plain` | 25 minutes |
| Android SDK36 build and lint | `sh gradlew --no-daemon --no-parallel --max-workers=1 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain` | 30 minutes |
| Android instrumentation API36 | `sh gradlew --no-daemon --no-parallel --max-workers=1 connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.fsm.restart.ProcessRestartTest --console=plain`, then `bash ../scripts/ci/android_process_restart.sh` | 35 minutes; emulator step 25, boot 10 |

The PostgreSQL15.10 service has a health check and an isolated job-lifetime DB.
Its published password is intentionally a disposable CI fixture, not a secret
or reusable deployment credential. Tests use their existing disposable-schema
logic. No app server or production configuration is needed. The environment-
specific backend `smoke-local.py` is deliberately not run by these jobs.

Android builds use the existing checksum-verifying Gradle8.13 Linux launcher,
SDK platform36 and build-tools36.0.0. Backend uses its existing Gradle8.14.3
wrapper. Both launchers run with `sh`, without changing executable bits or
requiring the separate Windows wrapper PR. A future wrapper change must retain
this Linux entry point; Windows execution is not covered by this workflow.

The separate emulator job runs the complete existing suite, not selected smoke
tests: API36 / google_apis / x86_64, 2 cores / 2048 MiB, KVM required. Missing KVM
fails explicitly; no software-emulator success substitute, paid/custom runner,
self-hosted machine, repository setting change or external device service.
The Room/media/engine suite uses fixtures and mock HTTP, not a live backend.
This is one emulator configuration, not a minSdk26-to36/device/Windows matrix,
physical-camera/GPS/Keystore/FGS acceptance, or full end-to-end validation.

## Evidence and failure policy

`scripts/ci/check_reports.py` requires XML and zero failure/error/skipped cases.
Baseline floors are 9 backend unit, 11 PostgreSQL, 13 Android JVM, and 14 Android
instrumentation tests. Instrumentation also requires each of the fourteen
baseline `com.fsm.RoomTest` methods by name, so replacing the suite with unrelated
tests or APK compilation cannot pass the gate. Additional passing tests are
allowed. Suite failures and emulator boot failures stay red; no continue-on-error,
retry-until-green, exclusion of ordinary cases, or ignored suite. The new
restart phase class is explicitly excluded from this ordinary invocation and
required separately below; its methods are not skipped/ignored testcases. Gradle's own lint gate stays
unchanged (existing warnings are not claimed fixed).

Reports and Android APKs are uploaded even after failures, when generated, with
7-day retention. Artifacts are evidence, not proof that all checks passed;
missing artifacts warn and do not turn a failed step green. The gate also writes
actual testcase-entry counts (`tests`, `failure`, `error`, `skips`) to the job
summary and public notice annotations, including before rejecting a failed suite.
Counts come from testcase children, not untrusted suite-total attributes; duplicate
entries remain counted and every bad entry fails the gate. Baseline names are
still checked as a set; duplicates cannot replace a missing required name.

For up to 20 bad entries per suite, error annotations/summary include the status,
allowlisted classname/method and exception type (otherwise `unknown`), and category
`assertion`/`timeout`/`other`. At most three unique source locations are emitted
from an explicit class/file mapping: `com.fsm.RoomTest` / `RoomTest.kt`,
`com.fsm.ContractTest` / `ContractTest.kt`, `com.fsm.ui.UiTextTest` / `UiTextTest.kt`,
and `com.fsm.ui.LifecycleUiTest` / `LifecycleUiTest.kt` (the latter from PR9).
Only these constant class/file labels and a positive line number (1–9999999)
appear in frame output; report-supplied frame methods and synthetic suffixes are
not echoed. Other owners/files, including invented or malformed packages, are
omitted. No backend frames are currently mapped. Only the first 65536 Python
characters of each status node's direct text are scanned; full XML is parsed first.

The helper does not echo raw exception messages, complete stacks, nested XML or
system-out. Identifier fields still come from untrusted XML after ASCII syntax
and length checks: this is not source authentication or a guarantee that a valid
identifier/line number cannot encode private data. Never put real private data
in test identifiers or reports. Each output is capped at 1000 characters before
workflow-command data escaping; unsupported identifiers become `unknown`.
Additional bad entries are explicitly reported as truncated, not ignored by the
gate. Unknown codecs/malformed XML fail with fixed errors without parser text.
If summary writing fails, the gate exits nonzero with one fixed console error,
without reopening that sink or printing the exception/path.

GitHub may show fewer annotations than emitted; the summary/artifact remains the
fallback when available. This does not sanitize Gradle's own logs or the existing
report artifacts. Check both Gradle and report-gate results; backend/JVM gates run
only after their build step succeeds, while the device gate also runs after a
non-cancelled test failure.
No user `.env`, local credentials, tokens, production DBs or private device data
are read, cached or uploaded. Never broaden artifacts to the whole checkout,
Gradle home or environment dumps.

## Separate process force-stop protocol

The emulator action keeps the emulator alive for both commands; failure of either
command fails the step. Before any restart actions, the existing XML gate and
`check_process_restart.py` require all 21 ordinary cases (15 Room, 6 lifecycle),
exact class/method names once each, with zero failure/error/skips. Empty, duplicate,
missing, or phase-contaminated XML fails. Ordinary reports remain separate from
the two selected restart methods; they do not claim 23 ordinary XML cases.

`android_process_restart.sh` invokes the gates and a Python standard-library-only
host protocol, on one `emulator-*` device, GitHub-hosted runner, Android user0,
fixed target `com.fsm` and runner
`com.fsm.test/androidx.test.runner.AndroidJUnitRunner`. Every device command is
serial-scoped. Before the first warm launch a read-only `run-as` preflight rejects
any account preference entries (including backup), `fsm_*` database files,
account-directory children (including hidden entries), `tokens_*` preference files,
or previous restart markers. Empty account preference maps and unrelated
WorkManager databases are permitted. Symlinked storage roots are rejected.
Nothing is deleted, reset, installed or opened as an account by the host.

Airplane mode is enabled and Wi-Fi/mobile data disabled before **each** warm
launch. The host requires airplane=1, wifi=0, mobile_data=0 and
`dumpsys connectivity` reporting `Active default network: none`. No later command
enables networking; this state is also checked after verification without
reapplying settings. This is a bounded disposable-emulator protocol, not evidence
of offline operation on arbitrary devices or a continuous packet capture.

The host warms `com.fsm/.MainActivity`, records its sole PID privately, then uses
`am instrument -w -r --no-restart` with exact
`com.fsm.restart.ProcessRestartTest#seedDurableState`. Both phases receive a new
lowercase UUIDv4 `restartRunId` and `restartSeedPid`; verification additionally
receives `restartVerifyPid`. The host requires exactly one started and one passed
case of the selected class/method, no other statuses, and final instrumentation
code -1. A successful adb exit or marker alone cannot pass. After seed finishes,
the same PID must still exist. Only then does the host issue
`am force-stop --user 0 com.fsm`, require process absence, warm a different PID,
and attach `#verifyDurableStateAfterForceStop` with `--no-restart` again. That PID
must also remain alive after instrumentation finishes. Commands have deadlines
(20s normally, 40s warm launch, 180s per instrumentation phase) and finite polls;
the existing 25-minute action/35-minute job deadlines remain outer bounds.

There is no reinstall, clear-data, activity-only fallback, software-emulator
fallback, blanket kill, or retry-until-green. This proves explicit force-stop and
fresh Application startup against durable Room/photo/outbox state, not Android
low-memory-killer behavior or automatic worker recovery. The test first checks the
unchanged RUNNING checkpoint, then explicitly exercises repository recovery;
unexpected worker interference fails rather than being labelled data loss.

Only strict own-run JSON is copied to `android-process-restart-api36` (7 days):
`manifest.json`, `seed-result.json`, `verify-result.json`, `host-proof.json`.
The manifest accepts only the agreed exact schema, UUIDv4 identities, lowercase
hashes, two photo rows and two ACKED-upload/RUNNING-close operation rows. It has
no request bodies, credentials, GPS/comments, or raw DTOs. Duplicate/extra keys,
foreign account/run IDs, malformed hashes/ownership/state, non-JSON numbers and
oversized markers fail. Result markers require exact fixed true booleans. Host
proof contains only schemaVersion and fixed booleans, never PIDs or arguments.
Only fixed stage errors are printed; raw device output, exception messages and
unvalidated markers are neither annotated nor saved as artifacts. Partial host
proof remains failed, not a substitute for both passing phases. The test's
owned-UUID cleanup is performed only after its verification assertions; host
failure never authorizes deletion of unknown data.

This configuration is not execution evidence. Initial local checks use synthetic
fixtures only; combined Android compilation and a real hosted run remain required.

## Security and resource limits

Only `contents: read`; checkout does not persist credentials. PRs use
`pull_request`, never `pull_request_target`. No repository secrets, OIDC, signing,
deployment, comment-writing or settings permissions. Fork PRs may require normal
GitHub approval; their cache writes are scoped by GitHub's PR cache isolation.
Treat all PR reports/APKs as untrusted artifacts. Actions are immutable commit
pins; do not replace them with moving tags without review.

Setup-java caches Gradle dependencies keyed by the relevant build files; test
outputs/APKs/AVDs are not reused as test evidence. No AVD snapshots are cached.
Gradle has one worker/no parallel tasks, respecting existing heap limits.
Concurrency cancels superseded runs for the same PR/ref. The three jobs run on
separate hosted runners, not on the shared 4GB development sandbox. Feature branch
pushes are intentionally not a second trigger: the draft PR starts CI and later
pushes share its concurrency group. Standard public-repository runners are in scope;
no larger-runner labels or paid services are configured. Normal GitHub artifact
storage/retention policies still apply.

Pins cover action source, Gradle distributions and SDK/build-tools package
versions, not the entire hosted OS, Temurin patch, emulator binary, platform-tools,
Google image revision or PostgreSQL image digest. These upstream patches may
change; record the actual job log environment when diagnosing a failure.

## Verification status

Configuration alone is not a green run. See the PR checks and linked workflow
runs for execution status; do not infer device execution from assemble tasks.
Local YAML/static/report-gate checks are distinct from hosted Gradle and emulator
execution. If Actions is disabled, awaiting approval, or cannot start until a
maintainer action, report that blocker without merging to activate CI.

## Official references checked during setup

- [GitHub hosted runner virtualization](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [GitHub workflow syntax and permissions](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [Workflow run read API](https://docs.github.com/en/rest/actions/workflow-runs)
- [setup-java v5](https://github.com/actions/setup-java/tree/b6effb05e454b25005698d916606bdc6ffcbf961)
- [checkout v6](https://github.com/actions/checkout/tree/d23441a48e516b6c34aea4fa41551a30e30af803)
- [upload-artifact v6](https://github.com/actions/upload-artifact/tree/b7c566a772e6b6bfb58ed0dc250532a479d7789f)
- [Android emulator runner v2.34.0, KVM and supported inputs](https://github.com/ReactiveCircus/android-emulator-runner/tree/1dcd0090116d15e7c562f8db72807de5e036a4ed)

- [AndroidJUnitRunner class/method and notClass selection](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner)
- [Android16 am --no-restart implementation](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/cmds/am/src/com/android/commands/am/Am.java)
- [Android16 attach to existing process and finish without force-stop](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)
- [Android16 default-network diagnostic](https://android.googlesource.com/platform/packages/modules/Connectivity/+/android-16.0.0_r1/service/src/com/android/server/ConnectivityService.java)
- [Android16 svc Wi-Fi/data command delegation](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/cmds/svc/svc.sh)
