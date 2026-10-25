#!/usr/bin/env bash
# Only hosted disposable resources. All child output is captured/sanitized by Python.
set -euo pipefail
umask 077
root="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$root"
case "${1:-}" in
  prepare|run|cleanup) python3 scripts/ci/check_service_e2e.py "$1" ;;
  *) echo '::error::Service E2E invalid entry point'; exit 1 ;;
esac
