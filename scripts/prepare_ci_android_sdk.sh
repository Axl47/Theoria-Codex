#!/usr/bin/env bash
# The hosted runner's old avdmanager misparses Major.Minor SDK packages as android-0.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This setup is only for ephemeral GitHub runners.' >&2; exit 2; }
readonly sdk="${ANDROID_HOME:?ANDROID_HOME is required}"
readonly runner_temporary="${RUNNER_TEMP:?RUNNER_TEMP is required}"
readonly tools_version=22.0
readonly tools="$sdk/cmdline-tools/$tools_version"

"$sdk/cmdline-tools/latest/bin/sdkmanager" --install "cmdline-tools;$tools_version"
[[ -x "$tools/bin/avdmanager" && -x "$tools/bin/sdkmanager" ]]
if [[ ! -L "$sdk/cmdline-tools/latest" || "$(readlink "$sdk/cmdline-tools/latest")" != "$tools" ]]; then
  mv "$sdk/cmdline-tools/latest" "$runner_temporary/android-command-line-tools-preinstalled"
  ln -s "$tools" "$sdk/cmdline-tools/latest"
fi
resolved_version="$("$sdk/cmdline-tools/latest/bin/sdkmanager" --version | tr -d '\r')"
[[ "$resolved_version" == "$tools_version" ]] || { echo "Expected SDK tools $tools_version, found $resolved_version." >&2; exit 2; }
printf '%s\n' "$resolved_version"
