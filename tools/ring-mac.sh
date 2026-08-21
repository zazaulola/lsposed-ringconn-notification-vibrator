#!/usr/bin/env bash
# Print the RingConn ring's Bluetooth name + MAC address (needed as a capture filter).
set -euo pipefail
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"

echo "Bonded/known Bluetooth devices matching 'Ring':"
"$ADB" shell dumpsys bluetooth_manager \
  | grep -iE "RingConn|Ring |address|mAddress" \
  | grep -iE "Ring|([0-9A-F]{2}:){5}[0-9A-F]{2}" \
  || echo "  (nothing matched — open the RingConn app and make sure the ring is connected, then retry)"

echo
echo "Tip: the advertised name 'RingConn Gen3-XXXX' ends in the last two bytes of the MAC."
