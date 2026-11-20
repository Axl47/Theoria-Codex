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

# Module layering that Gradle cannot see: a feature never depends on another feature, a kit never on a
# feature, and a kit only on kits to its left in ui-kit, media-kit, feed-kit.
module_layering() {
  local kits=(ui-kit media-kit feed-kit) build module name index failed=0
  for build in feature-*/build.gradle.kts *-kit/build.gradle.kts; do
    [[ -f "$build" ]] || continue
    module="${build%%/*}"
    for name in $(grep -oE 'project\("[^"]+"\)' "$build" | sed -E 's/project\(":?([^"]+)"\)/\1/'); do
      if [[ "$name" == feature-* && "$name" != "$module" ]]; then
        echo "$build depends on :$name; features and kits must not depend on a feature." >&2; failed=1
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
