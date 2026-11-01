#!/usr/bin/env bash
# Screenshots of every area of Sky Home Launcher, for the README (tools/update-screenshots.sh) or for comparing one build
# with another (tools/shotdiff.py).
#   tools/shots.sh <folder> [look] [theme] [area...]
#     look   glass | material3-expressive | - (keep the TV's)       default glass
#     theme  auto | light | dark | - (keep the TV's)                default dark
#     area   core | all | settings | home | catalog | episodes | saver | apps     default all
#            trailer (only by name, not in all: where the inline trailer fails, Play opens the YouTube app, which may show
#            someone's profile, so look at the shot before publishing it)
#            core = home, picks, catalog, screensaver and Settings' first screen (what every look and theme needs)
# Needs adb connected to the TV (adb connect <ip>:5555) with Sky Home Launcher installed and switched on. It drives the TV with
# remote keys, so leave the remote alone while it runs (about four minutes for all). It changes the Look and Theme settings
# and leaves them as you asked. It never force-stops the app (that would unbind the remote buttons' service).
# Tune for a TV: SHELF_RIGHTS (presses from the first shelf app to All Apps, default 5), CATALOG_ROWS (rows walked down
# from the shelf, default 8), TRAILER_ROW (the row, counted from the shelf, whose first title gets Play, default 4: the
# first catalog row), EPISODES_ROW (the "Shows you watch" row, whose first show gets OK, default 2; 0 skips it),
# SETTINGS_ROWS (the row numbers of Settings' main list to open, default 2 to 10) and SCROLL_ROWS (those long enough to
# scroll: two more screens each, default 2 3 7 8).
# Settings' rows: 0 Home Screen, 1 Screensaver, 2 Home Settings, 3 Shelf, 4 Top Buttons, 5 Remote Buttons, 6 Trakt, 7 TMDB,
# 8 Stremio, 9 Integrations, 10 Sleep Timer, 11 TV Settings (leaves the app: skipped), 12 Check for Updates (shown in the
# main list, so not opened).
set -euo pipefail
out="${1:?usage: tools/shots.sh <folder> [look] [theme] [area...]}"
look="${2:-glass}"
theme="${3:-dark}"
shift $(( $# < 3 ? $# : 3 ))
areas=("$@"); [ ${#areas[@]} -gt 0 ] || areas=(all)
shelf_rights="${SHELF_RIGHTS:-5}"
catalog_rows="${CATALOG_ROWS:-8}"
trailer_row="${TRAILER_ROW:-4}"
episodes_row="${EPISODES_ROW:-2}"
settings_rows="${SETTINGS_ROWS:-2 3 4 5 6 7 8 9 10}"
scroll_rows="${SCROLL_ROWS:-2 3 7 8}"
mkdir -p "$out"

want() {          # is this area asked for (all = everything, core = the small set)
  local a core="$2"
  for a in "${areas[@]}"; do
    [ "$a" = all ] || [ "$a" = "$1" ] && return 0
    [ "$a" = core ] && [ "$core" = core ] && return 0
  done
  return 1
}
key() { adb shell input keyevent "$1"; sleep "${2:-0.9}"; }
keys() { for k in "$@"; do key "$k" 0.5; done; sleep 0.6; }
repeat() { local n="$1" k="$2" i; for ((i = 0; i < n; i++)); do key "$k" 0.5; done; sleep 0.6; }
shot() { adb exec-out screencap -p > "$out/$1.png"; echo "  $1"; }

settings() {      # a fresh Settings (new task, so no force-stop); the look and theme go in on the first start
  adb shell am start -n com.skyhome.launcher/.SettingsActivity -f 0x10008000 "${extras[@]}" "$@" > /dev/null
  sleep 4
}
home() {          # the home screen with the focus on the shelf: Back climbs out of wherever the last walk left it
  adb shell input keyevent KEYCODE_HOME
  sleep 2
  repeat 12 KEYCODE_BACK
  sleep 1
}

extras=()
[ "$look" = - ] || extras+=(--es look "$look")
[ "$theme" = - ] || extras+=(--es theme "$theme")

adb shell input keyevent KEYCODE_WAKEUP
echo "Look $look, theme $theme, areas ${areas[*]} -> $out"

# Set the look and theme first; the home screen picks them up when it is shown again.
settings; sleep 1

if want settings core; then
  shot settings-main
fi
if want settings all; then
  for row in $settings_rows; do
    settings
    repeat "$row" KEYCODE_DPAD_DOWN
    key KEYCODE_DPAD_CENTER 2
    [ "$row" = 9 ] && sleep 2           # the phone-setup address and QR code
    name="settings-$(printf '%02d' "$row")"
    shot "$name"
    case " $scroll_rows " in            # a long list: two more screens down it
      *" $row "*) for part in b c; do repeat 9 KEYCODE_DPAD_DOWN; shot "$name$part"; done ;;
    esac
  done
  settings; key KEYCODE_DPAD_CENTER 2; shot settings-00-home-screen   # the Original / Sky choice, not changed
fi
if want saver core; then
  settings --ez screensaver true; sleep 4; shot screensaver
fi

if want home core; then
  home; sleep 6
fi
if want home core; then
  shot home
  key KEYCODE_DPAD_UP 1.2; shot home-picks
  key KEYCODE_DPAD_UP 1.2; shot home-top-buttons
  keys KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; shot home-top-buttons-2
  key KEYCODE_BACK 1.5
fi
if want apps all; then
  home; sleep 3
  repeat "$shelf_rights" KEYCODE_DPAD_RIGHT
  key KEYCODE_DPAD_CENTER 2.5; shot all-apps
  repeat 8 KEYCODE_DPAD_DOWN; shot all-apps-scrolled
  key KEYCODE_BACK 1.5
fi
if want catalog core; then
  home; sleep 3
  for ((i = 1; i <= catalog_rows; i++)); do
    key KEYCODE_DPAD_DOWN 1.8
    shot "page-row-$(printf '%02d' "$i")"
  done
fi
# Closing the episodes screen or a trailer's app drops the home screen back to the shelf, so each gets its own walk.
if want episodes all && [ "$episodes_row" -gt 0 ]; then
  home; sleep 3
  repeat "$episodes_row" KEYCODE_DPAD_DOWN
  key KEYCODE_DPAD_CENTER 4; shot episodes
  repeat 6 KEYCODE_DPAD_DOWN; shot episodes-scrolled
  key KEYCODE_BACK 3
fi
if [[ " ${areas[*]} " == *" trailer "* ]] && [ "$trailer_row" -gt 0 ]; then   # only when asked for by name
  home; sleep 3
  repeat "$trailer_row" KEYCODE_DPAD_DOWN
  key KEYCODE_MEDIA_PLAY_PAUSE "${TRAILER_WAIT:-16}"      # starts the trailer like a held OK, without a click that would open the title's app
  shot trailer
  key KEYCODE_BACK 2
fi
adb shell input keyevent KEYCODE_HOME
echo done
