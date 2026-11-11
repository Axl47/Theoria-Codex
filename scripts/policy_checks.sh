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

python_tests() {
  python3 -m unittest discover -s scripts/tests -p 'test_*.py'
  python3 -m unittest discover -s deploy/libretranslate -p 'test_*.py'
}
