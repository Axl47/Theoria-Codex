#!/usr/bin/env bash
# Usage: scripts/detekt_changed.sh [base-ref]   (default base: origin/main)
#
# Early warning for a pre-push hook: runs Detekt without type resolution on the production Kotlin
# files changed since the merge base with base-ref, committed or not, using the repo's Detekt config
# and baselines. Prints only findings and a one-line summary; exits non-zero on findings.
# The type-resolved Detekt in scripts/check.sh stays the authority.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

base="$(git merge-base "${1:-origin/main}" HEAD)"

# Changed or untracked Kotlin files that still exist, limited to the sources check.sh's Detekt tasks
# analyse: modules with Detekt applied, excluding test source sets.
files=()
while IFS= read -r file; do
  [[ -f "$file" ]] || continue
  [[ "$file" =~ ^(app|app-logic|core-[a-z-]+)/src/ && ! "$file" =~ /src/[A-Za-z]*[Tt]est[A-Za-z]*/ ]] || continue
  files+=("$PWD/$file")
done < <({ git diff --name-only "$base" -- '*.kt'; git ls-files --others --exclude-standard -- '*.kt'; } | sort -u)

(( ${#files[@]} > 0 )) || exit 0

# Resolve Detekt's CLI classpath through Gradle once, and again only when dependency versions change.
classpath_file=build/detekt-cli/classpath.txt
if [[ ! -s "$classpath_file" || gradle/libs.versions.toml -nt "$classpath_file" ]]; then
  ./gradlew --quiet writeDetektCliClasspath >/dev/null
fi

temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT

# One run covers every module, so merge the per-module baselines' suppressed IDs into one file.
{
  echo '<?xml version="1.0" ?>'
  echo '<SmellBaseline><ManuallySuppressedIssues/><CurrentIssues>'
  grep -h '<ID>' config/detekt/baseline-*.xml || true
  echo '</CurrentIssues></SmellBaseline>'
} > "$temp/baseline.xml"

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
input="$(IFS=:; echo "${files[*]}")"
status=0
"$java_bin" --add-opens java.base/java.lang=ALL-UNNAMED -cp "$(< "$classpath_file")" dev.detekt.cli.Main \
  --input "$input" \
  --config config/detekt/detekt.yml \
  --build-upon-default-config \
  --baseline "$temp/baseline.xml" \
  --base-path "$PWD" \
  --jvm-target 17 \
  > "$temp/out.txt" 2>&1 || status=$?

if (( status == 0 )); then
  echo "Detekt (changed files): no findings in ${#files[@]} file(s)."
  exit 0
fi
# Drop the CLI's own trailer; keep findings and any error output.
grep -v -e '^$' -e '^Analysis failed' "$temp/out.txt" || true
echo "Detekt (changed files): $(grep -c '^[ew]: ' "$temp/out.txt" || true) finding(s) in ${#files[@]} file(s)."
exit "$status"
