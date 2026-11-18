#!/usr/bin/env bash
# Compose screenshots rendered by Robolectric (Roborazzi) from fixture data, without a device.
#   capture [<test filter>]  renders the screenshot tests, or any test that calls captureRoboImage, on this
#                            machine and prints the images. Off Linux they land in app/build/outputs/roborazzi
#                            and are for looking at only; on Linux they replace the reference images.
#   record                   replaces the reference images in app/src/test/screenshots with the Linux
#                            renders from Verify's run on the pushed HEAD. When a screenshot comparison
#                            fails there, that run renders every screen and uploads them. Commit the result.
# CI compares against the references with `scripts/check.sh --part screenshots`.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/policy_checks.sh

screenshot_tests='com.theoriacodex.app.screenshots.*'
references=app/src/test/screenshots

capture() {
  local filter="${1:-$screenshot_tests}" output=app/build/outputs/roborazzi
  if [[ "$(uname -s)" == Linux ]]; then
    output="$references"
    # A full capture is the whole reference set, so screens that no longer exist drop out.
    [[ "$filter" != "$screenshot_tests" ]] || rm -rf "$references"
  fi
  select_robolectric_jdk
  local started
  started="$(mktemp)"
  ./gradlew :app:testDebugUnitTest --tests "$filter" -Proborazzi.test.record=true --rerun --quiet
  find "$output" -name '*.png' -newer "$started" | sort
  rm -f "$started"
}

record() {
  local head branch run
  head="$(git rev-parse HEAD)"
  branch="$(git branch --show-current)"
  git fetch --quiet origin "$branch"
  [[ "$(git rev-parse "origin/$branch")" == "$head" ]] || { echo "Push $branch first: Verify renders the pushed HEAD." >&2; exit 2; }
  run="$(gh run list --workflow verify.yml --commit "$head" --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
  [[ -n "$run" ]] || { echo "No Verify run for $head yet. Open a PR or wait for it to start." >&2; exit 1; }
  gh run watch "$run" > /dev/null || true
  # Global: the exit trap runs after this function returns.
  downloaded="$(mktemp -d)"
  trap 'rm -rf "$downloaded"' EXIT
  if ! gh run download "$run" --name screenshots --dir "$downloaded" 2> /dev/null; then
    echo "Verify run $run uploaded no screenshots: the references already match, or the screenshots part did not run." >&2
    exit 1
  fi
  rm -rf "$references"
  mkdir -p "$references"
  cp "$downloaded"/*.png "$references/"
  git status --short -- "$references"
}

case "${1:-}" in
  capture) capture "${2:-}" ;;
  record) record ;;
  *) echo 'Usage: scripts/screenshots.sh capture [<test filter>] | record' >&2; exit 2 ;;
esac
