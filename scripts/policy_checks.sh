# Seconds-long checks that need no Gradle build, shared by scripts/check.sh (which runs them first)
# and scripts/hooks/pre-push. Source it from the repository root.

# Where this work left main, or the previous commit when HEAD is already on main.
diff_base() {
  local base
  base="$(git merge-base origin/main HEAD)"
  if [[ "$base" == "$(git rev-parse HEAD)" ]]; then git rev-parse HEAD^; else echo "$base"; fi
}

# Line budgets and the Detekt-baseline ratchet. Usage: line_budgets [<base>]
line_budgets() {
  python3 scripts/check_hotspots.py ${1:+--base "$1"}
}

# Project names a build file depends on, in either spelling: project(":x") or project(path = ":x").
project_dependencies() {
  grep -oE 'project\([[:space:]]*(path[[:space:]]*=[[:space:]]*)?"[^"]+"' "$1" | sed -E 's/.*":?([^"]+)"$/\1/'
}

# Module layering that Gradle cannot see: a feature never depends on another feature, a kit never on a
# feature, a kit only on kits to its left in ui-kit, media-kit, feed-kit, and nothing below the kits
# (app-logic, core-*) on a kit or a feature.
module_layering() {
  local kits=(ui-kit media-kit feed-kit) build module name index failed=0
  for build in feature-*/build.gradle.kts *-kit/build.gradle.kts app-logic/build.gradle.kts core-*/build.gradle.kts; do
    [[ -f "$build" ]] || continue
    module="${build%%/*}"
    for name in $(project_dependencies "$build"); do
      if [[ "$name" == feature-* && "$name" != "$module" ]]; then
        echo "$build depends on :$name; only :app may depend on a feature." >&2; failed=1
      fi
      if [[ "$module" != feature-* && "$module" != *-kit && "$name" == *-kit ]]; then
        echo "$build depends on :$name; app-logic and core modules must not depend on a kit." >&2; failed=1
      fi
      for (( index = 0; index < ${#kits[@]}; index++ )); do
        [[ "$module" == "${kits[index]}" ]] || continue
        if [[ "$name" != "$module" && " ${kits[*]:index} " == *" $name "* ]]; then
          echo "$build depends on :$name; a kit may only depend on kits before it (${kits[*]})." >&2; failed=1
        fi
      done
    done
  done
  return "$failed"
}

# Whether a file names "$2" exactly on a line that is not commented out. The second grep counts instead of
# stopping at the first match: stopping early kills the first grep, and under pipefail that reads as "no entry".
has_active_entry() {
  grep -vE '^[[:space:]]*(#|//)' "$1" | grep -cE "(^|[^A-Za-z0-9_-])$2([^A-Za-z0-9_-]|\$)" > /dev/null
}

# Every kit and feature in settings.gradle.kts must be in each hand-kept list of checked modules, or its
# tests, Detekt, lint, coverage and line budgets would be skipped without any failure.
module_registration() {
  local module failed=0
  for module in $(grep -E '^[[:space:]]*include\(' settings.gradle.kts | grep -oE '":[^"]+"' | tr -d '":' | grep -E '^(feature-.+|.+-kit)$'); do
    has_active_entry scripts/check.sh ":$module:testDebugUnitTest" || { echo "scripts/check.sh runs no unit tests for :$module." >&2; failed=1; }
    has_active_entry scripts/check.sh ":$module:detektDebug" || { echo "scripts/check.sh runs no Detekt for :$module." >&2; failed=1; }
    has_active_entry scripts/check.sh ":$module:lintDebug" || { echo "scripts/check.sh runs no lint for :$module." >&2; failed=1; }
    has_active_entry build.gradle.kts "kover\(project\(\":$module\"\)\)" || { echo "build.gradle.kts leaves :$module out of the coverage report." >&2; failed=1; }
    has_active_entry config/quality/hotspot-budgets.json "\"$module\"" || { echo "config/quality/hotspot-budgets.json has no line budgets for $module." >&2; failed=1; }
    [[ -f "config/detekt/baseline-$module-debug.xml" ]] || { echo "config/detekt/baseline-$module-debug.xml is missing." >&2; failed=1; }
  done
  return "$failed"
}

python_tests() {
  python3 -m unittest discover -s scripts/tests -p 'test_*.py'
  python3 -m unittest discover -s deploy/libretranslate -p 'test_*.py'
}

# Robolectric's Android SDK 36 sandbox refuses JVMs older than 21: falls back to Homebrew's JDK.
select_robolectric_jdk() {
  local java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java" java_major homebrew_jdk
  java_major="$("$java_bin" -version 2>&1 | sed -nE '1s/.*"([0-9]+).*/\1/p')"
  (( ${java_major:-0} < 21 )) || return 0
  homebrew_jdk="$(brew --prefix openjdk 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home"
  [[ -x "$homebrew_jdk/bin/java" ]] || { echo 'Set JAVA_HOME to JDK 21 or newer.' >&2; exit 2; }
  export JAVA_HOME="$homebrew_jdk"
}
