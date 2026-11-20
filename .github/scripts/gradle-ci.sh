#!/usr/bin/env bash
# Report Kotlin errors and test failures on the check itself as well as in the log.
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
./gradlew "$@" --no-daemon --console=plain 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if (( status != 0 )); then
  grep -E '^e: file:| > .* FAILED$|^.*[[:space:]]FAILED[[:space:]]*$|^Execution failed for task|^Caused by:' "$log" | head -25 | while IFS= read -r line; do
    printf '::error::%s\n' "${line//%/%25}"
  done
fi
exit "$status"
