#!/usr/bin/env bash
# The one definition of "verified", shared by this Mac and GitHub. Cheap policy checks run first so they
# fail in seconds (the Python unittests only once, in the static part), then the chosen part:
#   tests    unit tests, coverage, and changed-code coverage for every module
#   static   Detekt with type resolution, duplicate code, configuration cache
#   lint     Android lint
#   release  R8 builds: release, releaseAcceptance, and benchmark builds when the change can affect them
#   screenshots  Compose screenshot tests against their reference images. References are Linux renders, so
#            this part runs only on Linux (GitHub) and is recorded as its own result. See scripts/screenshots.sh.
# Without --part it runs all three, the pre-PR check: one Gradle build of what they share, then lint
# beside the tests and Detekt (see run_check). GitHub runs the three parts as parallel jobs. Release
# builds and emulator tests run on GitHub after merge to main.
#
# --record (pre-PR check only) publishes the pass as refs/verified/<key>, where the key names the
# inputs that can affect the result (scripts/release_gate.py input-key), so GitHub's Verify and
# Release can reuse it. It needs a clean checkout. Optional locally; PRs normally let Verify run it.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

usage() { echo 'Usage: scripts/check.sh [--part tests|static|lint|release|screenshots] [--record]' >&2; exit 2; }
part=''
record=false
while (( $# )); do
  case "$1" in
    --part) [[ "${2:-}" =~ ^(tests|static|lint|release|screenshots)$ ]] || usage; part="$2"; shift ;;
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

# Stops the lanes still running when the check ends early, and waits for them. A lane is a subshell, so
# its command is signalled too: stopping the subshell alone would leave that Gradle build running.
stop_lanes() {
  local pid children
  for pid in $(jobs -p); do
    children="$(pgrep -P "$pid" || true)"
    kill "$pid" $children 2>/dev/null || true
  done
  wait
}
trap 'stop_lanes; write_timings; rm -rf "$temp"' EXIT

# Lanes run side by side. Usage: lane <name> <command...> for each, then finish_lanes, which waits for
# all of them, records each as a phase, prints the output of the ones that failed, and exits if any did.
lanes=()
lane() {
  local log="$temp/lane-${#lanes[@]}.log" started
  started="$(now_us)"
  ( trap 'now_us > "$log.end"' EXIT; "${@:2}" > "$log" 2>&1 ) &
  lanes+=("$!|$started|$log|$1")
}

finish_lanes() {
  local entry pid started log name ok us failed=()
  for entry in "${lanes[@]}"; do
    IFS='|' read -r pid started log name <<< "$entry"
    if wait "$pid"; then ok=true; else ok=false; failed+=("$name"); fi
    us=$(( $(< "$log.end") - started ))
    phases+=("$(printf '{"name": "%s", "seconds": %d.%03d, "ok": %s}' "$name" $(( us / 1000000 )) $(( us % 1000000 / 1000 )) "$ok")")
    if [[ "$ok" == true ]]; then
      echo "$name: passed in $(( us / 1000000 ))s"
    else
      echo "----- $name: FAILED after $(( us / 1000000 ))s. Its output:"
      cat "$log"
      echo "----- end of $name"
    fi
  done
  lanes=()
  if (( ${#failed[@]} )); then
    (IFS=,; echo "Failed: ${failed[*]}" >&2)
    exit 1
  fi
}

# Everything except the agent tool directory, which is local and never part of the build.
worktree_changes() { git status --porcelain -- . ':!.claude'; }

commit="$(git rev-parse HEAD)"
if [[ "$record" == true && -n "$(worktree_changes)" ]]; then
  echo 'Commit or discard changes first: --record must describe exactly the committed code.' >&2
  exit 2
fi

# Install the tracked pre-push hook unless this clone already chose its own hooks.
[[ -n "$(git config --get core.hooksPath || true)" ]] || git config core.hooksPath scripts/hooks

source scripts/policy_checks.sh
select_robolectric_jdk
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

# On GitHub's 4-vCPU runners a full rebuild of these tasks is CPU-bound: Robolectric suites slow each
# other down. Measured in Nov 2026 on full rebuilds (build cache off, 3+ runs each); PRs whose tasks
# mostly come from the build cache gain nothing from either change:
# - Requesting :app's tasks first ran slower (median 561s vs 451s): :app's suite took ~190s beside the
#   others instead of ~55s alone at the end.
# - Splitting these tasks across two runners saved ~75s (median 376s for the slower half); after a
#   Kover merge job and with lint (~5 min) as the next floor, not worth a second coverage path.
# Revisit if the runners' core count changes, or the tests part (ci-timings) runs more than ~2 minutes
# longer than lint.
test_tasks=(
  :core-domain:test
  :core-data:test
  :core-sources:test
  :core-stubs:test
  :app-logic:test
  :core-data-android:testDebugUnitTest
  :core-data-android:compileDebugAndroidTestKotlin
  :extension-host:testDebugUnitTest
  :extension-host:compileDebugAndroidTestKotlin
  :ui-kit:testDebugUnitTest
  :media-kit:testDebugUnitTest
  :media-kit:compileDebugAndroidTestKotlin
  :vision-kit:testDebugUnitTest
  :feed-kit:testDebugUnitTest
  :feature-viewer:testDebugUnitTest
  :feature-search:testDebugUnitTest
  :feature-library:testDebugUnitTest
  :feature-discover:testDebugUnitTest
  :feature-series:testDebugUnitTest
  :feature-settings:testDebugUnitTest
  :app:testDebugUnitTest
  :app:compileDebugAndroidTestKotlin
  :app:assembleDebug
  :koverXmlReportQuality
  :koverVerifyQuality
)
detekt_tasks=(
  :app:detektDebug
  :app-logic:detektMain
  :core-data-android:detektDebug
  :extension-host:detektDebug
  :ui-kit:detektDebug
  :media-kit:detektDebug
  :vision-kit:detektDebug
  :feed-kit:detektDebug
  :feature-viewer:detektDebug
  :feature-search:detektDebug
  :feature-library:detektDebug
  :feature-discover:detektDebug
  :feature-series:detektDebug
  :feature-settings:detektDebug
  :core-domain:detektMain
  :core-data:detektMain
  :core-sources:detektMain
  :core-stubs:detektMain
)
lint_tasks=(:app:lint :core-data-android:lintDebug :extension-host:lintDebug :ui-kit:lintDebug :media-kit:lintDebug :vision-kit:lintDebug :feed-kit:lintDebug :feature-viewer:lintDebug :feature-search:lintDebug :feature-library:lintDebug :feature-discover:lintDebug :feature-series:lintDebug :feature-settings:lintDebug)
# Everything lint shares with the tests and Detekt: all compilation and code generation. Once these
# are built, a lint build and a tests-and-Detekt build have no task left in common, so the two can
# run at the same time without writing the same output or reading sources mid-generation.
# `./gradlew <tasks> --dry-run` on each side lists the graphs if this needs rechecking.
shared_tasks=(
  :app:assembleDebug
  :app:compileDebugUnitTestKotlin
  :app:compileDebugUnitTestJavaWithJavac
  :app:compileDebugAndroidTestKotlin
  :core-data-android:compileDebugUnitTestKotlin
  :core-data-android:compileDebugUnitTestJavaWithJavac
  :core-data-android:compileDebugAndroidTestKotlin
  :core-data-android:bundleLibRuntimeToJarDebug
  :extension-host:compileDebugUnitTestKotlin
  :extension-host:compileDebugUnitTestJavaWithJavac
  :extension-host:compileDebugAndroidTestKotlin
  :extension-host:bundleLibRuntimeToJarDebug
  :media-kit:compileDebugAndroidTestKotlin
  :ui-kit:compileDebugUnitTestKotlin
  :ui-kit:compileDebugUnitTestJavaWithJavac
  :ui-kit:bundleLibRuntimeToJarDebug
  :media-kit:compileDebugUnitTestKotlin
  :media-kit:compileDebugUnitTestJavaWithJavac
  :media-kit:bundleLibRuntimeToJarDebug
  :vision-kit:compileDebugUnitTestKotlin
  :vision-kit:compileDebugUnitTestJavaWithJavac
  :vision-kit:bundleLibRuntimeToJarDebug
  :feed-kit:compileDebugUnitTestKotlin
  :feed-kit:compileDebugUnitTestJavaWithJavac
  :feed-kit:bundleLibRuntimeToJarDebug
  :feature-viewer:compileDebugUnitTestKotlin
  :feature-viewer:compileDebugUnitTestJavaWithJavac
  :feature-viewer:bundleLibRuntimeToJarDebug
  :feature-search:compileDebugUnitTestKotlin
  :feature-search:compileDebugUnitTestJavaWithJavac
  :feature-search:bundleLibRuntimeToJarDebug
  :feature-library:compileDebugUnitTestKotlin
  :feature-library:compileDebugUnitTestJavaWithJavac
  :feature-library:bundleLibRuntimeToJarDebug
  :feature-discover:compileDebugUnitTestKotlin
  :feature-discover:compileDebugUnitTestJavaWithJavac
  :feature-discover:bundleLibRuntimeToJarDebug
  :feature-series:compileDebugUnitTestKotlin
  :feature-series:compileDebugUnitTestJavaWithJavac
  :feature-series:bundleLibRuntimeToJarDebug
  :feature-settings:compileDebugUnitTestKotlin
  :feature-settings:compileDebugUnitTestJavaWithJavac
  :feature-settings:bundleLibRuntimeToJarDebug
  :core-domain:testClasses
  :core-data:testClasses
  :core-sources:testClasses
  :core-stubs:testClasses
  :app-logic:testClasses
)

changed_coverage() {
  python3 scripts/check_changed_coverage.py \
    --xml build/reports/kover/quality.xml \
    --base "$base" \
    --boundaries config/quality/coverage-boundaries.json \
    --minimum 60
}

# A fresh process: a daemon reused after other analysis can crash lint's Kotlin resolver. Offline
# keeps lint from looking up dependency versions on the network.
lint_build() {
  ./gradlew "${task_timings[@]}" "${lint_tasks[@]}" --offline --no-daemon --warning-mode=all --console=plain
}

# The pre-PR check keeps a daemon that only ever runs lint: warm, it analyses the app in about 50s
# where a fresh process takes 80s. Its heap size differs from the main daemon's on purpose: Gradle
# only keeps a second daemon when its JVM settings differ, and only then does no other analysis
# share lint's process. The periodic collection hands its heap back while it idles, and it inherits
# the environment's daemon idle timeout instead of pinning RAM for an hour. If lint fails there,
# that daemon is stopped and a fresh process decides, so a stale analysis session cannot fail the
# check and real findings still do.
lint_daemon_jvmargs='-Xmx2048m -XX:G1PeriodicGCInterval=60000 -Dfile.encoding=UTF-8'

# Gradle gives a build to any idle daemon started with the same JVM settings, so the daemons carrying
# every one of lint's settings are the ones lint could have run in. No other daemon is touched.
stop_lint_daemons() {
  ps -axo pid=,command= | awk -v settings="$lint_daemon_jvmargs" '
    /GradleDaemon/ {
      count = split(settings, setting, " ")
      for (i = 1; i <= count; i++) if (!index($0, " " setting[i] " ")) next
      print $1
    }' | xargs kill 2>/dev/null || true
}

lint_in_daemon() {
  ./gradlew "${task_timings[@]}" "${lint_tasks[@]}" --offline --warning-mode=all --console=plain \
    "-Dorg.gradle.jvmargs=$lint_daemon_jvmargs" && return
  echo 'Lint failed in its daemon. Stopping that daemon and retrying in a fresh process.'
  stop_lint_daemons
  lint_build
}

run_tests() {
  # Naming the Kover tasks is what turns on test instrumentation (see build.gradle.kts).
  phase 'Gradle: tests and coverage' ./gradlew "${task_timings[@]}" "${test_tasks[@]}" \
    --warning-mode=all --console=plain
  phase 'Changed-code coverage' changed_coverage
}

run_static() {
  phase 'npm ci' install_node_modules
  phase 'Duplicate audit' npm run audit:duplicates
  phase 'Gradle: configuration-cache check' configuration_cache_check
  phase 'Gradle: Detekt' ./gradlew "${task_timings[@]}" "${detekt_tasks[@]}" --warning-mode=all --console=plain
}

run_lint() {
  # Lint runs offline, so compile its inputs online first: that downloads their dependencies and
  # finishes all source generation before lint reads Room's generated Java.
  phase 'Gradle: lint inputs' ./gradlew "${task_timings[@]}" \
    :app:compileDebugKotlin \
    :core-data-android:compileDebugKotlin \
    :ui-kit:compileDebugKotlin \
    :media-kit:compileDebugKotlin \
    :vision-kit:compileDebugKotlin \
    :feed-kit:compileDebugKotlin \
    :feature-viewer:compileDebugKotlin \
    :feature-search:compileDebugKotlin \
    :feature-library:compileDebugKotlin \
    :feature-discover:compileDebugKotlin \
    :feature-series:compileDebugKotlin \
    :feature-settings:compileDebugKotlin \
    --warning-mode=all \
    --console=plain

  # Offline lint cannot download its own tool on a fresh machine. A dry run fetches it.
  phase 'Gradle: lint dry run' ./gradlew "${task_timings[@]}" "${lint_tasks[@]}" --dry-run --console=plain

  phase 'Gradle: lint' lint_build
}

# The pre-PR check on one machine. Lint analyses the whole app on one thread for most of a minute, so
# it runs beside the tests and Detekt instead of after them; their output is shown only if they fail.
run_check() {
  lane 'Python unittests' python_tests
  phase 'Gradle: shared build' ./gradlew "${task_timings[@]}" "${shared_tasks[@]}" --warning-mode=all --console=plain
  phase 'Gradle: lint dry run' ./gradlew "${task_timings[@]}" "${lint_tasks[@]}" --dry-run --console=plain
  lane 'Gradle: lint' lint_in_daemon
  lane 'Gradle: tests, coverage and Detekt' ./gradlew "${task_timings[@]}" "${test_tasks[@]}" "${detekt_tasks[@]}" \
    --warning-mode=all --console=plain
  phase 'npm ci' install_node_modules
  phase 'Duplicate audit' npm run audit:duplicates
  finish_lanes
  phase 'Changed-code coverage' changed_coverage
  phase 'Gradle: configuration-cache check' configuration_cache_check
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
  phase 'Extension keep rules' extension_symbols_kept
}

# Extensions call the app's classes by name, which the shrinker cannot see. Every name a sample of them used
# (extension-host/extension-symbols.txt) must be one R8 reports it was told to keep. A list or report that is missing or
# empty fails the check: neither can show that anything was kept.
extension_symbols_kept() {
  local symbols=extension-host/extension-symbols.txt seeds=app/build/outputs/mapping/release/seeds.txt wanted missing status=0
  [[ -s "$symbols" && -r "$symbols" ]] || { echo "$symbols is missing or empty." >&2; return 1; }
  [[ -s "$seeds" && -r "$seeds" ]] || { echo "$seeds is missing or empty: the release build did not report what R8 kept." >&2; return 1; }
  wanted="$(grep -v '^#' "$symbols")" || { echo "$symbols lists no symbols." >&2; return 1; }
  # grep exits 1 when every symbol is in the report, and above 1 when it could not read it.
  missing="$(grep -Fvxf "$seeds" <<<"$wanted")" || status=$?
  ((status <= 1)) || { echo "Could not compare $symbols with $seeds." >&2; return 1; }
  [[ -z "$missing" ]] && return
  printf 'extension-host/consumer-rules.pro no longer keeps what extensions call:\n%s\n' "$missing" >&2
  return 1
}

# Reference images are inputs Gradle does not track, so the comparison always reruns.
run_screenshots() {
  [[ "$(uname -s)" == Linux ]] || { echo 'Screenshots are compared only on Linux, where their references were rendered.' >&2; exit 2; }
  phase 'Gradle: screenshots' ./gradlew "${task_timings[@]}" :app:testDebugUnitTest --tests 'com.theoriacodex.app.screenshots.*' \
    -Proborazzi.test.verify=true --rerun --warning-mode=all --console=plain
}

# Limit concurrent local check runs across worktrees and wards so multiple agents don't exhaust RAM.
# Held by a helper process so persistent Gradle/Kotlin daemons never inherit the lock FD; stop_lanes
# terminates the helper on exit and releases the slot. Set THEORIA_CHECK_SLOTS=0 to bypass.
acquire_check_slot() {
  [[ "${GITHUB_ACTIONS:-}" != true ]] || return 0
  local slots="${THEORIA_CHECK_SLOTS:-1}"
  [[ "$slots" =~ ^(0|[1-9][0-9]*)$ ]] || { echo "THEORIA_CHECK_SLOTS must be a non-negative integer: $slots" >&2; exit 2; }
  (( slots > 0 )) || return 0
  local lock_dir="${THEORIA_CHECK_LOCK_DIR:-/tmp/theoria-codex-check-slots-$(id -u)}"
  local ready_file="$temp/check-slot.ready"
  python3 scripts/check_slot.py \
    --dir "$lock_dir" \
    --slots "$slots" \
    --ready-file "$ready_file" \
    --parent-pid "$$" &
  local holder_pid=$!
  while [[ ! -f "$ready_file" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      wait "$holder_pid" || return $?
      [[ -f "$ready_file" ]] || return 1
    fi
    sleep 0.2
  done
}

phase 'Hotspot check' line_budgets "$base"
phase 'Module layering' module_layering
phase 'Comment ratchet' comment_ratchet
phase 'Module registration' module_registration
[[ "$part" != static ]] || phase 'Python unittests' python_tests
acquire_check_slot

case "$part" in
  '') run_check ;;
  tests) run_tests ;;
  static) run_static ;;
  lint) run_lint ;;
  release) run_release ;;
  screenshots) run_screenshots ;;
esac

if [[ "$record" == true ]]; then
  if [[ "$(git rev-parse HEAD)" != "$commit" || -n "$(worktree_changes)" ]]; then
    echo 'The checkout changed during the run, so this result cannot be recorded.' >&2
    exit 1
  fi
  python3 scripts/release_gate.py record check "$commit"
fi
