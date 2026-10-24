#!/usr/bin/env bash
# Screenshots of the same screens, for comparing one build with another (tools/shotdiff.py).
#   tools/shots.sh <folder> [look] [theme]      look: glass | material3-expressive, theme: auto | light | dark
# Needs adb connected to the TV (adb connect <ip>:5555) with Free My TV installed and switched on. It drives the TV with
# remote keys, so leave the remote alone while it runs (about two minutes). It changes the Look and Theme settings, and
# puts them back to what they were when it is done only if you pass nothing: pass both to keep what you asked for.
set -euo pipefail
out="${1:?usage: tools/shots.sh <folder> [look] [theme]}"
look="${2:-glass}"
theme="${3:-dark}"
mkdir -p "$out"

key() { adb shell input keyevent "$1"; sleep "${2:-0.9}"; }
shot() { adb exec-out screencap -p > "$out/$1.png"; echo "  $1"; }
keys() { for k in "$@"; do key "$k"; done; }
settings() {      # a fresh Settings with the look and theme set first
  adb shell am force-stop io.freemytv
  adb shell am start -n io.freemytv/.SettingsActivity --es look "$look" --es theme "$theme" "$@" > /dev/null
  sleep 4
}
home() {
  adb shell am force-stop io.freemytv
  adb shell input keyevent KEYCODE_HOME
  sleep 9
}

adb shell input keyevent KEYCODE_WAKEUP
echo "Look $look, theme $theme -> $out"

settings; shot settings-main
keys KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 1; shot settings-home
settings; keys KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 1; shot settings-tmdb
keys KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 1; shot settings-catalogs
settings; keys KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 1; shot settings-stremio
settings --ez screensaver true; sleep 3; shot screensaver

home; shot home
key KEYCODE_DPAD_UP 1.2; shot home-cards
key KEYCODE_DPAD_UP 1.2; shot home-tabs
keys KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; sleep 0.5; shot home-buttons
home; keys KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 4; shot drawer
home; keys KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 2; shot all-apps
adb shell input keyevent KEYCODE_HOME
echo done
