#!/usr/bin/env bash
# Retakes the README's screenshots (screenshots/) from the TV: every area in Glass dark and Material dark, and the core set (home,
# picks, catalog, screensaver, Settings) in Glass light and Material 3 Expressive light.
#   tools/update-screenshots.sh [look-theme...]      e.g. glass-dark material-light; default: all four
# Files are screenshots/<look>-<theme>-<area>.jpg (look: glass | material). Needs what tools/shots.sh needs (adb connected,
# Sky Home Launcher on, the remote left alone: about 20 minutes for all four) and Pillow (pip install pillow) to turn the
# TV's PNGs into JPEGs. Old files of a combination are replaced only after its run finishes. It leaves the TV's Look and
# Theme as the last combination: change them back in Settings > Home Settings if you want.
set -euo pipefail
cd "$(dirname "$0")/.."
py=$(command -v py || command -v python3)
combos=("$@"); [ ${#combos[@]} -gt 0 ] || combos=(glass-dark glass-light material-dark material-light)
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for combo in "${combos[@]}"; do
  case "$combo" in
    glass-*) look=glass ;;
    material-*) look=material3-expressive ;;
    *) echo "unknown combination: $combo (glass-dark, glass-light, material-dark, material-light)" >&2; exit 1 ;;
  esac
  theme="${combo#*-}"
  area=core; [ "$combo" = glass-dark ] || [ "$combo" = material-dark ] && area=all
  echo "== $combo ($area)"
  bash tools/shots.sh "$work/$combo" "$look" "$theme" "$area"
  mkdir -p screenshots
  rm -f screenshots/"$combo"-*.jpg
  "$py" -I - "$work/$combo" screenshots "$combo" <<'PY'
import glob, os, sys
from PIL import Image
src, dst, prefix = sys.argv[1:4]
for f in sorted(glob.glob(os.path.join(src, '*.png'))):
    name = os.path.splitext(os.path.basename(f))[0]
    Image.open(f).convert('RGB').save(os.path.join(dst, f'{prefix}-{name}.jpg'), quality=90, optimize=True)
PY
done
adb shell input keyevent KEYCODE_HOME
echo "done: $(ls screenshots | wc -l) files, $(du -sh screenshots | cut -f1)"
