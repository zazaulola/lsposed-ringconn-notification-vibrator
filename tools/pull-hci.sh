#!/usr/bin/env bash
# Pull the Bluetooth HCI snoop log off the phone into ./captures/btsnoop_hci.log
#
# Prerequisite (do this ONCE, by hand, on the phone):
#   Settings > System > Developer options > "Enable Bluetooth HCI snoop log" -> Enabled (Full)
#   then toggle Bluetooth OFF and ON so the log starts clean.
#
# Usage:
#   tools/pull-hci.sh            # non-rooted: via `adb bugreport`
#   tools/pull-hci.sh --root     # rooted: direct adb pull (faster)
set -euo pipefail
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
OUT="captures"
mkdir -p "$OUT"
DEST="$OUT/btsnoop_hci.log"

if [[ "${1:-}" == "--root" ]]; then
  echo "Pulling directly (rooted)…"
  "$ADB" root >/dev/null 2>&1 || true
  for p in /data/misc/bluetooth/logs/btsnoop_hci.log /data/log/bt/btsnoop_hci.log; do
    if "$ADB" shell "test -f $p" 2>/dev/null; then
      "$ADB" pull "$p" "$DEST" && { echo "Saved $DEST"; exit 0; }
    fi
  done
  echo "Could not find the snoop log at the usual rooted paths." >&2
  exit 1
fi

echo "Requesting a bugreport (this takes 1-3 minutes)…"
ZIP="$OUT/bugreport.zip"
"$ADB" bugreport "$ZIP"

echo "Extracting btsnoop from the bugreport…"
INNER=""
for cand in FS/data/log/bt/btsnoop_hci.log FS/data/misc/bluetooth/logs/btsnoop_hci.log; do
  if unzip -l "$ZIP" | grep -q "$cand"; then INNER="$cand"; break; fi
done
if [[ -z "$INNER" ]]; then
  echo "No btsnoop_hci.log inside the bugreport. Is HCI snoop logging enabled?" >&2
  echo "Files that look relevant:" >&2
  unzip -l "$ZIP" | grep -iE "snoop|bluetooth" >&2 || true
  exit 1
fi
unzip -o -j "$ZIP" "$INNER" -d "$OUT" >/dev/null
mv -f "$OUT/$(basename "$INNER")" "$DEST"
echo "Saved $DEST"
