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
| Android instrumentation API36 | `sh gradlew --no-daemon --no-parallel --max-workers=1 connectedDebugAndroidTest --console=plain` | 35 minutes; emulator step 25, boot 10 |

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
retry-until-green, test exclusion, or ignored suite. Gradle's own lint gate stays
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
allowlisted classname/method and exception type (otherwise `unknown`), category
`assertion`/`timeout`/`other`, and at most three unique `com.fsm.*` test-owned stack
frames. Frames must name a matching `*Test.kt`/`*Test.java` class/file and a positive
line number; only the first 64 KiB of each failure/error/skipped text is inspected.
No raw exception messages, complete stacks, nested XML text, system-out, requests,
credentials or photo/GPS/comment data are emitted by this diagnostic helper.
Identifiers are ASCII syntax/length allowlisted; each output is capped at 1000
characters and workflow-command data is escaped. Unsupported names/frames are
omitted or labelled `unknown`, never printed raw. Additional bad entries are
explicitly reported as truncated, not ignored by the gate. GitHub may show fewer
annotations than emitted; the step summary/artifact remains the fallback. This
does not sanitize Gradle's own logs or the existing report artifacts. Check both
Gradle and report-gate results; backend/JVM gates run only after their build step
succeeds, while the device gate also runs after a non-cancelled test failure.
No user `.env`, local credentials, tokens, production DBs or private device data
are read, cached or uploaded. Never broaden artifacts to the whole checkout,
Gradle home or environment dumps.

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
- [checkout v6](https://github.com/actions/checkout/tree/d23441a48e516b6c34aea4fa41551a30eaf803)
- [upload-artifact v6](https://github.com/actions/upload-artifact/tree/b7c566a772e6b6bfb58ed0dc250532a479d7789f)
- [Android emulator runner v2.34.0, KVM and supported inputs](https://github.com/ReactiveCircus/android-emulator-runner/tree/1dcd0090116d15e7c562f8db72807de5e036a4ed)
