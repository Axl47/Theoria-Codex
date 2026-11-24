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

# Closes "Application Not Responding" and "Application Error" dialogs already on screen, appending each to
# $1, and fails if one stays open. Closing one force-stops the frozen process, which Android restarts, but a
# hung System UI keeps its dialog until it recovers or the emulator restarts. The remaining arguments are the adb command to run, such as a bounded wrapper.
error_dialogs_closed() {
  local log="$1" windows
  shift
  windows="$("$@" shell dumpsys window windows)" || return 1
  grep -qE 'Application (Not Responding|Error):' <<< "$windows" || return 0
  printf '%s\n' "$windows" | grep -E 'Application (Not Responding|Error):' >> "$log"
  "$@" shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null || return 1
  windows="$("$@" shell dumpsys window windows)" || return 1
  ! grep -qE 'Application (Not Responding|Error):' <<< "$windows"
}

# Emulators only: cold boots can freeze System UI long enough for an "isn't responding" dialog that holds
# input focus and swallows system Back. Hides new error dialogs and closes open ones before device tests.
# App crashes still fail their tests. A physical phone is left untouched.
prepare_emulator_dialogs() {
  local target="$1" log="$2"
  local adb_command=("${ADB:-adb}" -s "$target")
  [[ "$("${adb_command[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || return 0
  "${adb_command[@]}" shell settings put global hide_error_dialogs 1
  error_dialogs_closed "$log" "${adb_command[@]}" || {
    echo "An error dialog stayed open on $target after closing it; restart the emulator." >&2
    return 2
  }
}
