#!/usr/bin/env bash
# The one definition of "verified", shared by this Mac and GitHub. Cheap policy checks run first so
# they fail in seconds. Emulator tests are not part of it: they run in the Release workflow.
#
# --record publishes the pass under refs/verified/<tree>, named after the exact code that was tested,
# so GitHub's Verify and Release can reuse it. It needs a clean checkout. Run it before opening a PR.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

record=false
case "${1:-}" in
  "") ;;
  --record) record=true ;;
  *) echo 'Usage: scripts/check.sh [--record]' >&2; exit 2 ;;
esac

# Everything except the agent tool directory, which is local and never part of the build.
worktree_changes() { git status --porcelain -- . ':!.claude'; }

commit="$(git rev-parse HEAD)"
tree="$(git rev-parse 'HEAD^{tree}')"
if [[ "$record" == true && -n "$(worktree_changes)" ]]; then
  echo 'Commit or discard changes first: --record must describe exactly the committed code.' >&2
  exit 2
fi

# Robolectric's Android SDK 36 sandbox refuses JVMs older than 21.
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_major="$("$java_bin" -version 2>&1 | sed -nE '1s/.*"([0-9]+).*/\1/p')"
if (( ${java_major:-0} < 21 )); then
  homebrew_jdk="$(brew --prefix openjdk 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home"
  [[ -x "$homebrew_jdk/bin/java" ]] || { echo 'Set JAVA_HOME to JDK 21 or newer.' >&2; exit 2; }
  export JAVA_HOME="$homebrew_jdk"
fi

# Changed-code budgets compare against where this work left main, or the last commit when on main.
base="$(git merge-base origin/main HEAD)"
if [[ "$base" == "$commit" ]]; then
  base="$(git rev-parse HEAD^)"
fi

[[ node_modules/.package-lock.json -nt package-lock.json ]] || npm ci
python3 scripts/check_hotspots.py --base "$base"
npm run audit:duplicates
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 -m unittest discover -s deploy/libretranslate -p 'test_*.py'

# Strict configuration cache must store and then reuse an isolated entry.
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
project_cache="$temp/config-cache"
reuse_log="$temp/config-cache-reuse.log"
reuse_signal="Configuration cache entry reused."
for _ in store reuse; do
  ./gradlew help \
    --project-cache-dir "$project_cache" \
    --configuration-cache \
    --configuration-cache-problems=fail \
    --console=plain 2>&1 | tee "$reuse_log"
done
if ! grep -Fqx "$reuse_signal" "$reuse_log"; then
  echo 'Second strict Gradle help invocation did not reuse configuration cache.' >&2
  exit 1
fi

# One invocation, so tests, assemblies, Detekt, and coverage share a single compile.
./gradlew \
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
  :app:assembleRelease \
  :app:verifyBenchmarkFixtureArtifact \
  :macrobenchmark:verifyMacrobenchmarkRunnerArtifact \
  :app:detektDebug \
  :app-logic:detektMain \
  :core-data-android:detektDebug \
  :core-domain:detektMain \
  :core-data:detektMain \
  :core-sources:detektMain \
  :core-stubs:detektMain \
  :koverXmlReportQuality \
  :koverVerifyQuality \
  --warning-mode=all \
  --console=plain

# Offline lint cannot download its own tool on a fresh machine. A dry run fetches it.
./gradlew :app:lint :core-data-android:lintDebug --dry-run --console=plain

# Finish all source generation before lint reads Room's generated Java.
./gradlew :app:lint :core-data-android:lintDebug --offline --no-daemon --warning-mode=all --console=plain

python3 scripts/check_changed_coverage.py \
  --xml build/reports/kover/quality.xml \
  --base "$base" \
  --boundaries config/quality/coverage-boundaries.json \
  --minimum 60

if [[ "$record" == true ]]; then
  if [[ "$(git rev-parse HEAD)" != "$commit" || -n "$(worktree_changes)" ]]; then
    echo 'The checkout changed during the run, so this result cannot be recorded.' >&2
    exit 1
  fi
  git push --quiet origin "+$commit:refs/verified/$tree"
  echo "Recorded verified code $tree."
fi
