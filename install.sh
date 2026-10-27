#!/usr/bin/env bash
# Installs SkyHomeLauncher.apk to one or more TVs over adb: ./install.sh <ip> [ip...]
set -euo pipefail
cd "$(dirname "$0")"

[ "$#" -ge 1 ] || { echo "Usage: $0 <ip> [ip...]"; exit 1; }
[ -f SkyHomeLauncher.apk ] || { echo "SkyHomeLauncher.apk not found. Run ./build.sh first."; exit 1; }

status=0
for ip in "$@"; do
  addr="$ip"
  [[ "$addr" == *:* ]] || addr="$addr:5555"
  echo "--- $addr"
  if ! adb connect "$addr"; then
    echo "$addr: could not connect"; status=1; continue
  fi
  if ! adb -s "$addr" install -r SkyHomeLauncher.apk; then
    echo "$addr: install failed"; status=1; continue
  fi
done
exit "$status"
