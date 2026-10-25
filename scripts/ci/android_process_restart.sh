#!/usr/bin/env bash
# Only disposable GitHub-hosted emulator; no cleanup, install, or data reset here.
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$root"
python3 scripts/ci/check_reports.py instrumentation
python3 scripts/ci/check_process_restart.py
