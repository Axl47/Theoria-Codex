#!/usr/bin/env bash
# Real-data tasks on Theoria Debug, which holds an imported copy of your library and accounts.
#   export-library DEST.json   Save Debug's library as a profile backup (no credentials or media) for
#                              BENCHMARK_LIBRARY in scripts/verify_performance_device.sh.
#   data-usage NEW_DIR         Run the fixed live browsing session (DATA_USAGE_RUNS times, default 3) and
#                              record what each run downloaded, per media stack. Each run first clears
#                              Debug's disposable media caches; DATA_USAGE_KEEP_CACHES=1 keeps them.
# Both update Debug in place like installDebug, keeping its data; they never uninstall or clear it.
set -euo pipefail

readonly repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo/scripts/device_validation_common.sh"
readonly mode="${1:-}"
if [[ $# != 2 || ( "$mode" != export-library && "$mode" != data-usage ) ]]; then
  echo 'Usage: ANDROID_SERIAL=SERIAL scripts/real_data_device.sh export-library DEST.json' >&2
  echo '   or: ANDROID_SERIAL=SERIAL [DATA_USAGE_RUNS=3] scripts/real_data_device.sh data-usage NEW_EVIDENCE_DIR' >&2
  exit 2
fi
readonly serial="${ANDROID_SERIAL:?Select the device with ANDROID_SERIAL}"
readonly destination="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$2")"
[[ ! -e "$destination" ]] || { echo 'Destination must be new; existing files are never overwritten.' >&2; exit 2; }
cd "$repo"

readonly debug_apk="app/build/outputs/apk/debug/app-debug.apk"
readonly test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
readonly runner="com.theoriacodex.debug.test/androidx.test.runner.AndroidJUnitRunner"
adb_command=("${ADB:-adb}" -s "$serial")
gradle_options=(--console=plain --quiet)
[[ "${THEORIA_DEVICE_OFFLINE:-0}" != 1 ]] || gradle_options+=(--offline)

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest "${gradle_options[@]}"
python3 scripts/verify_device_apk.py "$debug_apk" --package com.theoriacodex.debug
python3 scripts/verify_device_apk.py "$test_apk" --package com.theoriacodex.debug.test
require_connected_device "$serial"
"${adb_command[@]}" install -r -t "$debug_apk" > /dev/null
"${adb_command[@]}" install -r -t "$test_apk" > /dev/null

# Runs one opt-in class from app/src/androidTest/.../live and fails unless it passed.
run_live_class() {
  local class="$1" argument="$2" log="$3"
  shift 3
  "${adb_command[@]}" shell am instrument -w -r -e class "com.theoriacodex.app.live.$class" -e "$argument" true \
    "$@" "$runner" > "$log" 2>&1 || true
  grep -q '^OK (1 test)' "$log" || { echo "$class did not pass; see $log" >&2; exit 1; }
}

if [[ "$mode" == export-library ]]; then
  log="$(mktemp)"
  run_live_class LibrarySnapshotExportTest theoriaExportLibrary "$log"
  expected="$(sed -n 's/^INSTRUMENTATION_STATUS: theoriaLibraryBytes=//p' "$log" | tr -d '\r')"
  "${adb_command[@]}" exec-out run-as com.theoriacodex.debug cat cache/benchmark-library.json > "$destination"
  "${adb_command[@]}" shell run-as com.theoriacodex.debug rm -f cache/benchmark-library.json
  rm -f "$log"
  actual="$(wc -c < "$destination" | tr -d ' ')"
  [[ -n "$expected" && "$actual" == "$expected" ]] || { rm -f "$destination"; echo "Snapshot copy was incomplete." >&2; exit 1; }
  printf 'Library snapshot saved to %s (%s bytes). It holds your personal library; keep it out of the repository.\n' \
    "$destination" "$actual"
  exit 0
fi

mkdir -p "$destination"
{
  printf 'revision=%s\n' "$(git rev-parse HEAD)"
  printf 'debug_apk_sha256=%s\n' "$(shasum -a 256 "$debug_apk" | cut -d' ' -f1)"
  printf 'device=%s\n' "$("${adb_command[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
} > "$destination/build.txt"
for ((index=1; index<=${DATA_USAGE_RUNS:-3}; index++)); do
  printf 'Running live data-usage session %s/%s.\n' "$index" "${DATA_USAGE_RUNS:-3}"
  keep=()
  [[ "${DATA_USAGE_KEEP_CACHES:-0}" != 1 ]] || keep=(-e theoriaKeepCaches true)
  run_live_class LiveDataUsageSessionTest theoriaLiveDataUsage "$destination/run-$index.log" ${keep[@]+"${keep[@]}"}
  sed -n 's/^INSTRUMENTATION_STATUS: theoriaDataUsage=//p' "$destination/run-$index.log" | tr -d '\r' \
    > "$destination/run-$index.json"
done
python3 - "$destination" <<'PY'
import json, pathlib, statistics, sys
runs = [json.loads(path.read_text()) for path in sorted(pathlib.Path(sys.argv[1]).glob("run-*.json"))]
mb = lambda value: f"{value / 1_000_000:.1f} MB"
labels = {"image": "Images", "video": "Video", "download": "GIFs, Pixiv animations, offline saves",
          "source": "Provider APIs, indexes, length probes"}
rows = {"Total (app's whole network use)": [run["totalBytes"] for run in runs]}
rows |= {label: [run["stacks"][stack]["bytes"] for run in runs] for stack, label in labels.items()}
rows["Uncounted (headers, TLS, other)"] = [run["otherBytes"] for run in runs]
rows["Video read from cache (incl. prefetch)"] = [run["videoCachedBytesRead"] for run in runs]
summary = {name: statistics.median(values) for name, values in rows.items()}
for name, values in rows.items():
    print(f"{name:38} median {mb(summary[name]):>10}   runs: {', '.join(mb(v) for v in values)}")
images = [run["imageResultsBySource"] for run in runs]
print("Images by source (per run): " + "; ".join(", ".join(f"{k} {v}" for k, v in run.items()) for run in images))
print("Viewer opened: " + ", ".join(str(run["viewerOpened"]) for run in runs)
      + "   caches cleared: " + ", ".join(str(run["cachesCleared"]) for run in runs)
      + "   metered network: " + ", ".join(str(run["metered"]) for run in runs))
(pathlib.Path(sys.argv[1]) / "summary.json").write_text(json.dumps({"median_bytes": summary, "runs": len(runs)}, indent=2))
PY
