#!/usr/bin/env bash
# Report Kotlin errors and test failures on the check itself as well as in the log.
set -uo pipefail
log=$(mktemp)
trap 'rm -f "$log"' EXIT
./gradlew "$@" --no-daemon --console=plain 2>&1 | tee "$log"
status=${PIPESTATUS[0]}
if (( status != 0 )); then
  grep -E '^e: file:|^ERROR:.*R8|^Missing class|^AGPBI:.*"kind":"error"| > .* FAILED$|^.*[[:space:]]FAILED[[:space:]]*$|^Execution failed for task|^Caused by:' "$log" | head -25 | while IFS= read -r line; do
    printf '::error::%s\n' "${line//%/%25}"
  done
  if [[ " $* " == *" connectedDebugAndroidTest "* ]]; then
    python3 - <<'PY_REPORT'
import pathlib
import xml.etree.ElementTree as ET
for path in pathlib.Path("app/build/outputs/androidTest-results").rglob("TEST-*.xml"):
    for test in ET.parse(path).iter("testcase"):
        for problem in (*test.findall("failure"), *test.findall("error")):
            message = f"{test.get('classname')}.{test.get('name')}: {problem.get('message') or problem.text or 'failed'}"
            print("::error::" + message[:2500].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A"))
PY_REPORT
  fi
fi
exit "$status"
