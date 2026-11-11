#!/usr/bin/env bash
# Some hosted runner images leave too little disk for the emulator's userdata partition,
# so remove preinstalled toolchains these jobs never use. The Android SDK and Java stay.
# Most images have plenty of room, so the slow removal only runs when space is actually short.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'This setup is only for ephemeral GitHub runners.' >&2; exit 2; }
# The API 37 emulator alone needs about 7.4 GB; leave room for the system image and the build.
readonly required_kib=$((20 * 1024 * 1024))

df -h /
if (( $(df --output=avail -k / | tail -1) >= required_kib )); then
  echo 'Enough free disk; nothing removed.'
  exit 0
fi
sudo rm -rf /usr/share/dotnet /usr/local/.ghcup /opt/ghc /usr/share/swift /opt/hostedtoolcache/CodeQL
df -h /
