#!/usr/bin/env bash
# Build, preflight, measure and retain comparable evidence on one explicitly selected phone.
set -euo pipefail

readonly repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo/scripts/device_validation_common.sh"
readonly mode="${1:-}"
if [[ "$mode" == calibrate && $# == 2 ]]; then
  baseline=""
  destination="$2"
  captures=3
elif [[ "$mode" == compare && $# == 3 ]]; then
  baseline="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$2")"
  destination="$3"
  captures=1
  [[ -f "$baseline" ]] || { echo 'Baseline JSON is missing.' >&2; exit 2; }
else
  echo 'Usage: ANDROID_SERIAL=SERIAL scripts/verify_performance_device.sh calibrate NEW_EVIDENCE_DIR' >&2
  echo '   or: ANDROID_SERIAL=SERIAL scripts/verify_performance_device.sh compare BASELINE_JSON NEW_EVIDENCE_DIR' >&2
  exit 2
fi
readonly serial="${ANDROID_SERIAL:?Select one physical device with ANDROID_SERIAL}"
readonly output="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$destination")"
[[ ! -e "$output" ]] || { echo 'Evidence destination must be new; existing captures are never overwritten.' >&2; exit 2; }
mkdir -p "$output"
cd "$repo"

readonly target_apk="app/build/outputs/apk/benchmarkRelease/app-benchmarkRelease.apk"
readonly runner_apk="macrobenchmark/build/outputs/apk/benchmarkRelease/macrobenchmark-benchmarkRelease.apk"
readonly results="macrobenchmark/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected"
adb_command=("${ADB:-adb}" -s "$serial")

require_selected_phone() {
  [[ "$(selected_device)" == "$serial" ]]
  require_physical_device "$serial"
}

# A dry run is host-only. Actual package manifests and signatures are checked before connected work.
./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest --dry-run --console=plain > "$output/task-graph.txt" 2>&1
./gradlew :app:assembleBenchmarkRelease :macrobenchmark:assembleBenchmarkRelease --console=plain > "$output/build.log" 2>&1
if grep -Eq 'Startup (class|method) not found|missing startup classes and methods' "$output/build.log"; then
  echo 'Startup profiles are stale; regenerate in the isolated baseline-profile lane before measurement.' >&2
  exit 2
fi
python3 scripts/verify_device_apk.py "$target_apk" --package com.theoriacodex.benchmark > "$output/preflight.log"
python3 scripts/verify_device_apk.py "$runner_apk" --package com.theoriacodex.macrobenchmark >> "$output/preflight.log"
require_selected_phone
"${adb_command[@]}" shell getprop ro.build.fingerprint > "$output/device-fingerprint.txt"

run_records=()
for ((index=1; index<=captures; index++)); do
  require_selected_phone
  printf 'Running complete physical benchmark capture %s/%s.\n' "$index" "$captures"
  ./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest --console=plain > "$output/run-$index.log" 2>&1
  report="$(python3 - "$results" "$output/device-fingerprint.txt" <<'PY'
import json, pathlib, sys
fingerprint = pathlib.Path(sys.argv[2]).read_text().strip()
candidates = []
for path in pathlib.Path(sys.argv[1]).rglob('*benchmarkData.json'):
    report = json.loads(path.read_text())
    if report.get('context', {}).get('build', {}).get('fingerprint') == fingerprint:
        candidates.append(path)
if len(candidates) != 1:
    raise SystemExit('Expected exactly one full report for the verified phone; inspect connected outputs.')
print(candidates[0])
PY
)"
  python3 scripts/benchmark_results.py record \
    --report "$report" --apk "$target_apk" --harness-apk "$runner_apk" \
    --device-serial "$serial" --device-kind physical \
    --compilation 'Partial(BaselineProfileMode.Require,warmupIterations=0)' \
    --output "$output/run-$index"
  run_records+=("$output/run-$index/run.json")
done

if [[ "$mode" == calibrate ]]; then
  python3 scripts/benchmark_results.py calibrate --runs "${run_records[@]}" --output "$output/baseline.json"
else
  python3 scripts/benchmark_results.py compare --baseline "$baseline" \
    --candidate "${run_records[0]}" --output "$output/comparison.json"
fi
