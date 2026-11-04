#!/usr/bin/env bash
# Some hosted runner images leave too little disk for the emulator's userdata partition,
# so remove preinstalled toolchains these jobs never use. The Android SDK and Java stay.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This setup is only for ephemeral GitHub runners.' >&2; exit 2; }

df -h /
sudo rm -rf /usr/share/dotnet /usr/local/.ghcup /opt/ghc /usr/share/swift /opt/hostedtoolcache/CodeQL
df -h /
