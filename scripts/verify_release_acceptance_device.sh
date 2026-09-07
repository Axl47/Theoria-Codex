#!/usr/bin/env bash

set -euo pipefail

readonly package_name="com.theoriacodex.acceptance"
readonly activity_name="com.theoriacodex.app.MainActivity"
readonly apk_path="${1:-app/build/outputs/apk/releaseAcceptance/app-releaseAcceptance.apk}"
readonly script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly data_activity="com.theoriacodex.app.acceptance.ReleaseDataAcceptanceActivity"
readonly acceptance_token="$(python3 -c 'import secrets; print(secrets.token_hex(12))')"

adb_command=("${ADB:-adb}")
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_command+=("-s" "$ANDROID_SERIAL")
fi

adb_run() {
  "${adb_command[@]}" "$@"
}

wait_for_process() {
  local pid=""
  local attempt
  for attempt in {1..15}; do
    pid="$(adb_run shell pidof "$package_name" 2>/dev/null | tr -d '\r')"
    if [[ -n "$pid" ]]; then
      printf '%s\n' "$pid"
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_clean_launch() {
  local label="$1"
  shift
  local output
  output="$(adb_run shell am start -W "$@")"
  printf '%s\n' "$output"
  if ! grep -q "Status: ok" <<<"$output"; then
    printf 'Release acceptance %s launch did not report Status: ok.\n' "$label" >&2
    return 1
  fi

  local pid
  if ! pid="$(wait_for_process)"; then
    printf 'Release acceptance process exited after the %s launch.\n' "$label" >&2
    return 1
  fi
  printf 'Release acceptance %s process is alive as PID %s.\n' "$label" "$pid"
}

assert_minified_data_phase() {
  local phase="$1"
  local pid output attempt
  adb_run shell am force-stop "$package_name"
  assert_clean_launch "data $phase" -n "$package_name/$data_activity" \
    --es token "$acceptance_token" --es phase "$phase"
  pid="$(wait_for_process)"
  for attempt in {1..15}; do
    output="$(adb_run logcat -d --pid="$pid" -s 'TheoriaReleaseData:I' '*:S')"
    if grep -Fq "FAIL:$acceptance_token:$phase:" <<<"$output"; then
      printf '%s\n' "$output" >&2
      return 1
    fi
    if grep -Fq "PASS:$acceptance_token:$phase" <<<"$output"; then
      return 0
    fi
    sleep 1
  done
  printf 'Minified data %s did not report its matching completion token.\n' "$phase" >&2
  return 1
}

if [[ ! -f "$apk_path" ]]; then
  printf 'Release acceptance APK does not exist: %s\n' "$apk_path" >&2
  exit 1
fi

python3 "$script_directory/verify_device_apk.py" "$apk_path"

adb_run wait-for-device
adb_run install -r "$apk_path"

package_dump="$(adb_run shell dumpsys package "$package_name")"
if grep -Eq '(flags|pkgFlags)=\[[^]]*DEBUGGABLE' <<<"$package_dump"; then
  printf 'Release acceptance APK is unexpectedly debuggable.\n' >&2
  exit 1
fi
if ! grep -q "versionName=.*-acceptance" <<<"$package_dump"; then
  printf 'Installed package is not the release-acceptance variant.\n' >&2
  exit 1
fi

adb_run shell am force-stop "$package_name"
assert_clean_launch "cold start" -n "$package_name/$activity_name"

adb_run shell am force-stop "$package_name"
assert_clean_launch \
  "Pixiv callback" \
  -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d theoriacodex://pixiv-auth/callback \
  -n "$package_name/$activity_name"

readonly launch_pid="$(wait_for_process)"
crash_log="$(adb_run logcat -b crash -d --pid="$launch_pid")"
if grep -Eq "FATAL EXCEPTION|Process: $package_name" <<<"$crash_log"; then
  printf '%s\n' "$crash_log" >&2
  printf 'Release acceptance APK produced a crash-buffer entry.\n' >&2
  exit 1
fi

assert_minified_data_phase write
assert_minified_data_phase read

printf 'Minified release acceptance passed startup, callback, and persisted data across process restart.\n'
