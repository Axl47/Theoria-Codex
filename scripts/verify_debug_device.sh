#!/usr/bin/env bash
# Build and verify the exact APKs before serializing the selected instrumentation owners.
set -euo pipefail
readonly repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo/scripts/device_validation_common.sh"
cd "$repo"
readonly owner="${1:-all}"
if [[ $# -gt 1 || ( "$owner" != app && "$owner" != data && "$owner" != all ) ]]; then
  echo 'Usage: scripts/verify_debug_device.sh [app|data|all]' >&2
  exit 2
fi
if [[ "$owner" == all && -n "${DEVICE_TEST_CLASS:-}" ]]; then
  echo 'A class filter requires selecting its app or data owner.' >&2
  exit 2
fi
serial="$(selected_device)"
readonly serial
export ANDROID_SERIAL="$serial"
readonly evidence="build/reports/device-preflight/$(date -u +%Y%m%dT%H%M%S)-$$"
mkdir -p "$evidence"
connected=()
assemblies=()
test_options=()
if [[ -n "${DEVICE_TEST_CLASS:-}" ]]; then
  test_options+=("-Pandroid.testInstrumentationRunnerArguments.class=$DEVICE_TEST_CLASS")
fi
if [[ "$owner" == app || "$owner" == all ]]; then
  connected+=(":app:connectedDebugAndroidTest")
  assemblies+=(":app:assembleDebug" ":app:assembleDebugAndroidTest")
fi
if [[ "$owner" == data || "$owner" == all ]]; then
  connected+=(":core-data-android:connectedDebugAndroidTest")
  assemblies+=(":core-data-android:assembleDebugAndroidTest")
fi
./gradlew "${connected[@]}" "${test_options[@]}" --dry-run --no-parallel --console=plain > "$evidence/task-graph.txt" 2>&1
./gradlew "${assemblies[@]}" --console=plain > "$evidence/build.log" 2>&1
if [[ "$owner" == app || "$owner" == all ]]; then
  python3 scripts/verify_device_apk.py app/build/outputs/apk/debug/app-debug.apk --package com.theoriacodex.debug
  python3 scripts/verify_device_apk.py app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk --package com.theoriacodex.debug.test
fi
if [[ "$owner" == data || "$owner" == all ]]; then
  python3 scripts/verify_device_apk.py core-data-android/build/outputs/apk/androidTest/debug/core-data-android-debug-androidTest.apk --package com.theoriacodex.data.android.test
fi
current_device="$(selected_device)"
[[ "$current_device" == "$serial" ]]
test_status=0
./gradlew "${connected[@]}" "${test_options[@]}" --continue --no-parallel --console=plain || test_status=$?
if [[ "$owner" == app || "$owner" == all ]]; then
  # Read-only collection also runs after test failures; never hide the instrumentation exit status.
  "${ADB:-adb}" -s "$serial" pull /sdcard/Android/data/com.theoriacodex.debug/files/journey-diagnostics \
    "$evidence/" > "$evidence/journey-diagnostics-pull.log" 2>&1 || true
fi
exit "$test_status"
