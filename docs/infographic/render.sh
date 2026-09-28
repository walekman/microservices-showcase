#!/usr/bin/env sh
# Renders infographic.html to infographic.png (next to it) with headless Chrome.
# Needs network access (fonts and icons come from CDNs). Set CHROME if Chrome isn't at the default path.
set -e
cd "$(dirname "$0")"
DIR="$(pwd -W 2>/dev/null || pwd)"
CHROME="${CHROME:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 \
  --window-size=1600,1110 --virtual-time-budget=15000 \
  --screenshot="$DIR/infographic.png" \
  "file:///$DIR/infographic.html"
