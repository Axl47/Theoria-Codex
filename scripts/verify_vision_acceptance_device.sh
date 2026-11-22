#!/usr/bin/env bash
# Picture tagger acceptance on an emulator, through the minified acceptance APK's own container:
# download and verify the model, index the Library in the charging-only job (and not on battery), a predicted
# tag reaching For You, a hidden tag catching an untagged post, and delete. See VisionAcceptanceActivity.
#
# Usage: scripts/verify_vision_acceptance_device.sh <model-dir> [apk]
#   <model-dir> holds wd-vit-tagger-v3.int8.tflite and wd-vit-tagger-v3.selected_tags.csv (the pinned files).
#   Build the APK with: ./gradlew :app:assembleReleaseAcceptance -Ptheoria.visionModelBaseUrl=http://127.0.0.1:8765/
# Set ANDROID_SERIAL to the emulator. It refuses a device that is not an emulator.
set -euo pipefail

readonly model_dir="$1"
readonly apk_path="${2:-app/build/outputs/apk/releaseAcceptance/app-releaseAcceptance.apk}"
readonly package_name="com.theoriacodex.acceptance"
readonly activity="com.theoriacodex.app.acceptance.VisionAcceptanceActivity"
readonly job_id=3102
readonly port=8765
readonly script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

adb_command=("${ADB:-adb}")
[[ -n "${ANDROID_SERIAL:-}" ]] && adb_command+=("-s" "$ANDROID_SERIAL")
adb_run() { "${adb_command[@]}" "$@"; }

if [[ "$(adb_run shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  printf 'Refusing: the target is not an emulator.\n' >&2
  exit 1
fi
python3 "$script_directory/verify_device_apk.py" "$apk_path"

serve="$(mktemp -d)"
cleanup() {
  adb_run shell dumpsys battery reset >/dev/null 2>&1 || true
  adb_run reverse --remove "tcp:$port" >/dev/null 2>&1 || true
  [[ -n "${server_pid:-}" ]] && kill "$server_pid" 2>/dev/null || true
  rm -rf "$serve"
}
trap cleanup EXIT
ln -s "$(realpath "$model_dir")"/wd-vit-tagger-v3.* "$serve/"
python3 "$script_directory/vision_fixtures.py" "$serve/fixtures" >/dev/null
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$serve" >/dev/null 2>&1 &
server_pid=$!
adb_run reverse "tcp:$port" "tcp:$port" >/dev/null

adb_run install -r "$apk_path" >/dev/null
adb_run shell pm clear "$package_name" >/dev/null
adb_run shell dumpsys battery unplug

# Runs one phase and waits for its PASS or FAIL line: in a fresh process, or with "same" in the running one.
phase() {
  local name="$1" line attempt
  [[ "${2:-}" == same ]] || adb_run shell am force-stop "$package_name"
  adb_run logcat -c
  # Clearing the top recreates the activity, so a phase in the running process starts again too.
  adb_run shell am start -W --activity-clear-top -n "$package_name/$activity" --es phase "$name" >/dev/null
  for attempt in $(seq 1 300); do
    line="$(adb_run logcat -d TheoriaVision:I '*:S' | grep -Eo '(PASS|FAIL):.*' | tail -1 || true)"
    if [[ -n "$line" ]]; then
      printf '%s\n' "$line"
      [[ "$line" == PASS:* ]] && return 0
      return 1
    fi
    sleep 1
  done
  printf 'TIMEOUT:%s\n' "$name"
  return 1
}

job_state() { adb_run shell dumpsys jobscheduler "$package_name" | grep -m1 -A3 "#u0a[0-9]*/$job_id" | tr -s ' ' | tr '\n' ' '; }

phase download
phase seed
phase idle
printf 'Job on battery: %s\n' "$(job_state)"
# Asked to run unplugged, the job must not start: the same process still has nothing tagged. (-s honours every
# constraint; without it, the command ignores charging.)
printf 'Run on battery: %s\n' "$(adb_run shell cmd jobscheduler run -s "$package_name" "$job_id" 2>&1 | tr -d '\r' || true)"
phase idle same
adb_run shell dumpsys battery set ac 1
adb_run shell dumpsys battery set status 2
adb_run shell cmd jobscheduler run -s "$package_name" "$job_id" >/dev/null
phase check same
phase delete
printf 'Vision acceptance passed.\n'
