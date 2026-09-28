#!/usr/bin/env sh
# Renders infographic.html to docs/images/infographic.png with headless Chrome.
# Needs network access (fonts and icons come from CDNs). Set CHROME if Chrome isn't at the default path.
set -e
cd "$(dirname "$0")"
CHROME="${CHROME:-/c/Program Files/Google/Chrome/Application/chrome.exe}"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1.5 \
  --window-size=1600,1150 --virtual-time-budget=15000 \
  --screenshot="$(cd ../images && pwd -W 2>/dev/null || pwd)/infographic.png" \
  "file:///$(pwd -W 2>/dev/null || pwd)/infographic.html"
