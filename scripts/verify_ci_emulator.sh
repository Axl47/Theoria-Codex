#!/usr/bin/env bash
# Retain environment failures separately from app assertions; never run on a personal phone.
set -euo pipefail
readonly repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo"
readonly owner="${1:-}"
[[ "$owner" == debug || "$owner" == acceptance ]] || { echo 'Expected debug or acceptance.' >&2; exit 2; }
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'CI emulator wrapper requires GitHub Actions.' >&2; exit 2; }
readonly probe_timeout=10
readonly readiness_timeout=60

# Bound the entire client process group, including children of the device-selection shell.
run_with_timeout() {
  python3 - "$@" <<'PY'
import os
import signal
import subprocess
import sys

process = subprocess.Popen(sys.argv[2:], start_new_session=True)
try:
    status = process.wait(timeout=float(sys.argv[1]))
except subprocess.TimeoutExpired:
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()
    print(f"CI emulator command timed out after {sys.argv[1]}s: {sys.argv[2:]}", file=sys.stderr)
    sys.exit(124)
sys.exit(status if status >= 0 else 128 - status)
PY
}

serial="$(run_with_timeout "$probe_timeout" bash -c 'source scripts/device_validation_common.sh; selected_device')"
readonly serial
[[ "$serial" == emulator-* ]] || { echo 'CI diagnostics must not access a physical phone.' >&2; exit 2; }
export ANDROID_SERIAL="$serial"
adb_command=("${ADB:-adb}" -s "$serial")
finite_adb() { run_with_timeout "$probe_timeout" "${adb_command[@]}" "$@"; }
[[ "$(finite_adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]]
readonly evidence="build/reports/ci-emulator/$owner"
mkdir -p "$evidence"
"${adb_command[@]}" logcat -b all -v threadtime > "$evidence/logcat.txt" 2>&1 &
readonly logcat_pid=$!

finish() {
  local status=$?
  # This is our own streaming client; force termination so wait cannot delay artifact upload.
  kill -KILL "$logcat_pid" 2>/dev/null || true
  wait "$logcat_pid" 2>/dev/null || true
  finite_adb shell dumpsys dropbox --print system_server_crash > "$evidence/system-server-crash.txt" 2>&1 || true
  finite_adb shell df -k /data /sdcard > "$evidence/storage-after.txt" 2>&1 || true
  finite_adb shell cat /proc/meminfo > "$evidence/memory-after.txt" 2>&1 || true
  exit "$status"
}
trap finish EXIT

readonly avd_root="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
cp "$avd_root/test.ini" "$evidence/avd.ini"
cp "$avd_root/test.avd/config.ini" "$evidence/avd-config.ini"
sdk_level="$(finite_adb shell getprop ro.build.version.sdk | tr -d '\r')"
avd_api="$(sed -n 's/^target=android-//p' "$evidence/avd.ini" | tr -d '\r')"
[[ "$avd_api" =~ ^[0-9]+(\.[0-9]+)?$ && "${avd_api%%.*}" == "$sdk_level" ]] || {
  echo 'AVD target does not match its system image; check the SDK command-line tools.' >&2
  exit 2
}

# A boot-completed property alone does not prove package/activity services or shared storage work.
ready=false
readonly readiness_deadline=$((SECONDS + readiness_timeout))
ready_adb() {
  local remaining=$((readiness_deadline - SECONDS))
  (( remaining > 0 )) || return 124
  if (( remaining > probe_timeout )); then remaining=$probe_timeout; fi
  run_with_timeout "$remaining" "${adb_command[@]}" "$@"
}
while (( SECONDS < readiness_deadline )); do
  if [[ "$(ready_adb shell am get-current-user 2>/dev/null | tr -d '\r')" == 0 ]] &&
     [[ "$(ready_adb shell am get-started-user-state 0 2>/dev/null | tr -d '\r')" == RUNNING_UNLOCKED ]] &&
     ready_adb shell pm path android > "$evidence/package-service.txt" 2>&1 &&
     ready_adb shell df -k /data /sdcard > "$evidence/storage-before.txt" 2>&1; then
    ready=true
    break
  fi
  if (( SECONDS < readiness_deadline )); then sleep 1; fi
done
[[ "$ready" == true ]] || { echo 'Emulator package/user/storage services are not ready.' >&2; exit 2; }
finite_adb shell cat /proc/meminfo > "$evidence/memory-before.txt"
if (( sdk_level >= 37 )); then
  # A 4 GiB VM reports slightly less usable RAM after kernel reservations.
  awk '/^MemTotal:/ { found=1; if ($2 < 3500000) exit 1 } END { if (!found) exit 1 }' "$evidence/memory-before.txt" || {
    echo 'Android 17 requires a 4 GiB AVD; inspect memory-before.txt.' >&2
    exit 2
  }
fi

if [[ "$owner" == debug ]]; then
  scripts/verify_debug_device.sh all
else
  scripts/verify_release_acceptance_device.sh
fi
