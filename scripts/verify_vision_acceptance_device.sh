#!/usr/bin/env bash
# Picture tagger acceptance on an emulator, through the minified acceptance APK's own container:
# download and verify the model, index the Library in the charging-only job (and not on battery), video frames read
# only once the app leaves the screen, a predicted tag reaching For You, a hidden tag catching an untagged post, and
# delete. See VisionAcceptanceActivity. Fixture videos are made from the fixture drawings with ffmpeg (libx264 and
# libvpx-vp9) and served with byte ranges; the bytes each sampled video cost are printed.
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
# Six line drawings, one per second, keyframes once a second: a fast-start MP4, one with its index at the end, and WebM.
video_args=(-y -loglevel error -framerate 1 -i "$serve/fixtures/fixture-%02d.png" -t 6 -r 25 -g 25
  -vf "scale=360:360:force_original_aspect_ratio=decrease,pad=360:360:(ow-iw)/2:(oh-ih)/2:white,format=yuv420p")
ffmpeg "${video_args[@]}" -c:v libx264 -movflags +faststart "$serve/fixtures/video-start.mp4"
ffmpeg "${video_args[@]}" -c:v libx264 "$serve/fixtures/video-tail.mp4"
ffmpeg "${video_args[@]}" -c:v libvpx-vp9 -b:v 0 -crf 40 "$serve/fixtures/video.webm"
printf 'Fixture sizes: %s\n' "$(cd "$serve/fixtures" && stat -c '%n=%s' video* | tr '\n' ' ')"
served="$serve/served.log"
python3 "$script_directory/range_http_server.py" "$serve" "$port" "$served" &
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
# Forcing the job without --force must be refused while its charging constraint is unmet.
on_battery="$(adb_run shell cmd jobscheduler run -s "$package_name" "$job_id" 2>&1 | tr -d '\r' || true)"
printf 'Run on battery: %s\n' "$on_battery"
[[ "$on_battery" == *"functional constraints"* ]] || { echo "The indexing job ran on battery." >&2; exit 1; }
phase idle same
# Charging with the app on screen: the job indexes the stills and leaves the videos for a later run.
adb_run shell dumpsys battery set ac 1
adb_run shell dumpsys battery set status 2
adb_run shell cmd jobscheduler run -s "$package_name" "$job_id" >/dev/null
phase deferred same
# Off screen, the next run reads the videos' frames: leaving the screen schedules it, so it may start at once.
: >"$served"
adb_run logcat -c
adb_run shell input keyevent KEYCODE_HOME
sleep 2
# The deferring run asked to run again, so the job first waits out its backoff (about 30 s).
for attempt in $(seq 1 30); do
  started="$(adb_run shell cmd jobscheduler run -s "$package_name" "$job_id" 2>&1 | tr -d '\r' || true)"
  [[ "$started" == *constraints* ]] || break
  sleep 5
done
for attempt in $(seq 1 300); do
  # A finished run that left nothing to do drops the job, which get-job-state reports as an error.
  state="$(adb_run shell cmd jobscheduler get-job-state "$package_name" "$job_id" 2>&1 | tr -d '\r' || true)"
  [[ "$state" == *active* || "$state" == *ready* || "$state" == *pending* ]] || break
  sleep 1
done
printf 'Job after the off-screen run: %s\n' "$state"
printf 'Decoders: %s\n' "$(adb_run logcat -d | grep -Eo 'c2\.[a-z0-9]+\.[a-z0-9]+\.decoder' | sort | uniq -c | tr -s ' ' | tr '\n' ';')"
video_bytes() { awk '/video/ { bytes[$1] += $3; requests[$1]++ } END { for (path in bytes) printf "%s=%d/%dreq\n", path, bytes[path], requests[path] }' "$served" | sort | tr '\n' ' '; }
first_bytes="$(video_bytes)"
printf 'Bytes served per video: %s\n' "$first_bytes"
phase check same
: >"$served"
phase sample
# Sampling writes nothing to the cache, so sampling again fetches what the job's run did.
again_bytes="$(video_bytes)"
printf 'Bytes served per video, sampled again: %s\n' "$again_bytes"
if [[ "$again_bytes" != "$first_bytes" ]]; then
  printf 'FAIL:cache:sampling again fetched other bytes than the job run\n'
  exit 1
fi
phase delete
printf 'Vision acceptance passed.\n'
