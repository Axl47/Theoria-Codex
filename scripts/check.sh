#!/usr/bin/env bash
# The one definition of "verified", shared by this Mac and GitHub. Cheap policy checks run first so they
# fail in seconds (the Python unittests only once, in the static part), then the chosen part:
#   tests    unit tests, coverage, and changed-code coverage for every module
#   static   Detekt with type resolution, Android lint, duplicate code, configuration cache
#   release  R8 builds: release, releaseAcceptance, and benchmark builds when the change can affect them
# Without --part it runs tests then static: the pre-PR check. Release builds and emulator tests run on
# GitHub after merge to main.
#
# --record (pre-PR check only) publishes the pass as refs/verified/<key>, where the key names the
# inputs that can affect the result (scripts/release_gate.py input-key), so GitHub's Verify and
# Release can reuse it. It needs a clean checkout. Run it before opening a PR.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

usage() { echo 'Usage: scripts/check.sh [--part tests|static|release] [--record]' >&2; exit 2; }
part=''
record=false
while (( $# )); do
  case "$1" in
    --part) [[ "${2:-}" =~ ^(tests|static|release)$ ]] || usage; part="$2"; shift ;;
    --record) record=true ;;
    *) usage ;;
  esac
  shift
done
if [[ "$record" == true && -n "$part" ]]; then
  echo '--record records the full pre-PR check, so it cannot be combined with --part.' >&2
  exit 2
fi

# Phase timings for CI dashboards: build/reports/timings/ci-timings.json combines phases.json
# (written on every exit past argument parsing, even when a phase fails or none started) with
# gradle-tasks.json (written per task by the init script).
timings_dir=build/reports/timings
rm -rf "$timings_dir"
mkdir -p "$timings_dir"
task_timings=(--init-script gradle/task-timings.init.gradle.kts)
phases=()
current_phase=''
phase_started=0

# Microseconds since the epoch; whole seconds on shells older than bash 5.
now_us() {
  if [[ -n "${EPOCHREALTIME:-}" ]]; then echo "${EPOCHREALTIME/[.,]/}"; else echo "$(date +%s)000000"; fi
}

record_phase() {
  local us=$(( $(now_us) - phase_started ))
  phases+=("$(printf '{"name": "%s", "seconds": %d.%03d, "ok": %s}' "$current_phase" $(( us / 1000000 )) $(( us % 1000000 / 1000 )) "$1")")
  current_phase=''
}

# Usage: phase <name> <command...>. A failing command exits the script and is recorded by write_timings.
phase() {
  current_phase="$1"
  phase_started="$(now_us)"
  "${@:2}"
  record_phase true
}

write_timings() {
  [[ -z "$current_phase" ]] || record_phase false
  local IFS=,
  printf '[%s]\n' "${phases[*]}" > "$timings_dir/phases.json"
  printf '[%s]\n' "$(paste -sd, "$timings_dir/gradle-tasks.jsonl" 2>/dev/null)" > "$timings_dir/gradle-tasks.json"
  printf '{"version": 1, "phases": %s, "tasks": %s}\n' \
    "$(< "$timings_dir/phases.json")" "$(< "$timings_dir/gradle-tasks.json")" > "$timings_dir/ci-timings.json"
}

temp="$(mktemp -d)"
trap 'write_timings; rm -rf "$temp"' EXIT

# Everything except the agent tool directory, which is local and never part of the build.
worktree_changes() { git status --porcelain -- . ':!.claude'; }

commit="$(git rev-parse HEAD)"
if [[ "$record" == true && -n "$(worktree_changes)" ]]; then
  echo 'Commit or discard changes first: --record must describe exactly the committed code.' >&2
  exit 2
fi

# Install the tracked pre-push hook unless this clone already chose its own hooks.
[[ -n "$(git config --get core.hooksPath || true)" ]] || git config core.hooksPath scripts/hooks

# Robolectric's Android SDK 36 sandbox refuses JVMs older than 21.
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_major="$("$java_bin" -version 2>&1 | sed -nE '1s/.*"([0-9]+).*/\1/p')"
if (( ${java_major:-0} < 21 )); then
  homebrew_jdk="$(brew --prefix openjdk 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home"
  [[ -x "$homebrew_jdk/bin/java" ]] || { echo 'Set JAVA_HOME to JDK 21 or newer.' >&2; exit 2; }
  export JAVA_HOME="$homebrew_jdk"
fi

source scripts/policy_checks.sh
# Changed-code budgets compare against where this work left main, or the last commit when on main.
base="$(diff_base)"

install_node_modules() {
  [[ node_modules/.package-lock.json -nt package-lock.json ]] || npm ci
}

# Strict configuration cache must store and then reuse an isolated entry.
configuration_cache_check() {
  local project_cache="$temp/config-cache"
  local reuse_log="$temp/config-cache-reuse.log"
  local reuse_signal="Configuration cache entry reused."
  for _ in store reuse; do
    ./gradlew help "${task_timings[@]}" \
      --project-cache-dir "$project_cache" \
      --configuration-cache \
      --configuration-cache-problems=fail \
      --console=plain 2>&1 | tee "$reuse_log"
  done
  if ! grep -Fqx "$reuse_signal" "$reuse_log"; then
    echo 'Second strict Gradle help invocation did not reuse configuration cache.' >&2
    exit 1
  fi
}

run_tests() {
  # Naming the Kover tasks is what turns on test instrumentation (see build.gradle.kts).
  phase 'Gradle: tests and coverage' ./gradlew "${task_timings[@]}" \
    :core-domain:test \
    :core-data:test \
    :core-sources:test \
    :core-stubs:test \
    :app-logic:test \
    :core-data-android:testDebugUnitTest \
    :core-data-android:compileDebugAndroidTestKotlin \
    :app:testDebugUnitTest \
    :app:compileDebugAndroidTestKotlin \
    :app:assembleDebug \
    :koverXmlReportQuality \
    :koverVerifyQuality \
    --warning-mode=all \
    --console=plain

  phase 'Changed-code coverage' python3 scripts/check_changed_coverage.py \
    --xml build/reports/kover/quality.xml \
    --base "$base" \
    --boundaries config/quality/coverage-boundaries.json \
    --minimum 60
}

run_static() {
  phase 'npm ci' install_node_modules
  phase 'Duplicate audit' npm run audit:duplicates
  phase 'Gradle: configuration-cache check' configuration_cache_check

  phase 'Gradle: Detekt' ./gradlew "${task_timings[@]}" \
    :app:detektDebug \
    :app-logic:detektMain \
    :core-data-android:detektDebug \
    :core-domain:detektMain \
    :core-data:detektMain \
    :core-sources:detektMain \
    :core-stubs:detektMain \
    --warning-mode=all \
    --console=plain

  # Offline lint cannot download its own tool on a fresh machine. A dry run fetches it.
  phase 'Gradle: lint dry run' ./gradlew "${task_timings[@]}" :app:lint :core-data-android:lintDebug --dry-run --console=plain

  # Finish all source generation before lint reads Room's generated Java.
  phase 'Gradle: lint' ./gradlew "${task_timings[@]}" :app:lint :core-data-android:lintDebug --offline --no-daemon --warning-mode=all --console=plain
}

# Benchmark builds only matter when the change touches them or the build itself. On main there is no
# change to compare, so they always run.
benchmark_relevant() {
  [[ "$(git merge-base origin/main HEAD)" != "$commit" ]] || return 0
  grep -Eq \
    '^(macrobenchmark/|baseline-profile/|app/src/(benchmarkRelease|fixtures|release/generated/baselineProfiles)/|gradle/|gradle\.properties$|gradlew|(.*/)?[^/]*\.gradle\.kts$|scripts/verify_(benchmark_fixture|macrobenchmark_runner)_apk\.py$)' \
    <<< "$(git diff --name-only "$base" --)"
}

run_release() {
  local tasks=(:app:assembleRelease :app:assembleReleaseAcceptance)
  if benchmark_relevant; then
    tasks+=(:app:verifyBenchmarkFixtureArtifact :macrobenchmark:verifyMacrobenchmarkRunnerArtifact)
  else
    echo 'Skipping benchmark builds: nothing they depend on changed since main.'
  fi
  phase 'Gradle: release builds' ./gradlew "${task_timings[@]}" "${tasks[@]}" --warning-mode=all --console=plain
}

phase 'Hotspot check' line_budgets "$base"
[[ "$part" == tests || "$part" == release ]] || phase 'Python unittests' python_tests

case "$part" in
  '') run_tests; run_static ;;
  tests) run_tests ;;
  static) run_static ;;
  release) run_release ;;
esac

if [[ "$record" == true ]]; then
  if [[ "$(git rev-parse HEAD)" != "$commit" || -n "$(worktree_changes)" ]]; then
    echo 'The checkout changed during the run, so this result cannot be recorded.' >&2
    exit 1
  fi
  python3 scripts/release_gate.py record check "$commit"
fi
