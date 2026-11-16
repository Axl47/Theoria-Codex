#!/usr/bin/env bash
# Read-only device selection shared by isolated test and benchmark lanes.

selected_device() {
  local connected selected
  connected="$("${ADB:-adb}" devices | awk 'NR>1 && $2 == "device" {print $1}')"
  selected="${ANDROID_SERIAL:-$connected}"
  if [[ -z "$selected" || "$selected" == *$'\n'* || "$connected" != "$selected" ]]; then
    echo 'Exactly one authorized device must be connected; select it with ANDROID_SERIAL.' >&2
    return 2
  fi
  printf '%s\n' "$selected"
}

# For lanes that send every adb command with -s: only the named device must be connected and
# authorized, so a shared emulator may stay connected beside a phone.
require_connected_device() {
  if [[ "$("${ADB:-adb}" -s "$1" get-state 2>/dev/null | tr -d '\r')" != device ]]; then
    echo "Device $1 is not connected and authorized." >&2
    return 2
  fi
}

require_physical_device() {
  local qemu
  qemu="$("${ADB:-adb}" -s "$1" shell getprop ro.kernel.qemu | tr -d '\r')"
  if [[ "$qemu" == 1 || "$1" == emulator-* ]]; then
    echo 'Physical performance evidence cannot come from an emulator.' >&2
    return 2
  fi
}
