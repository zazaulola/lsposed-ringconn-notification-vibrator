#!/usr/bin/env bash
# Decode the phone->ring command writes from a captured HCI snoop log, so you can spot the
# vibrate command. Requires Wireshark's `tshark`.
#
# Usage:
#   tools/decode-writes.sh captures/btsnoop_hci.log                 # writes to handle 0x0802 only
#   tools/decode-writes.sh captures/btsnoop_hci.log --all           # every ATT write, with handles
#   tools/decode-writes.sh captures/btsnoop_hci.log --mac F8:79:99:F7:03:AD   # restrict to the ring
#
# Column output: <time>  <att-opcode>  handle=<h>  <hex payload>
# The RingConn command characteristic value handle is 0x0802; commands look like [cmd][sub]..[00],
# e.g. the device-verified Find-My-Ring LED is 24:01:00.
set -euo pipefail

LOG="${1:?path to btsnoop_hci.log}"
shift || true
command -v tshark >/dev/null || { echo "tshark not found. Install Wireshark (brew install --cask wireshark) or open the log in the Wireshark GUI." >&2; exit 1; }

HANDLE_FILTER="btatt.handle == 0x0802"
MAC_FILTER=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) HANDLE_FILTER="" ;;
    --mac) MAC_FILTER=" && bluetooth.dst == ${2}"; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

WRITE='(btatt.opcode.method == 0x12 || btatt.opcode.method == 0x52)'
FILTER="$WRITE"
[[ -n "$HANDLE_FILTER" ]] && FILTER="$FILTER && $HANDLE_FILTER"
FILTER="$FILTER$MAC_FILTER"

echo "# filter: $FILTER"
echo "# time            opcode  handle    value(hex)"
tshark -r "$LOG" -Y "$FILTER" \
  -T fields -E separator='  ' \
  -e frame.time_relative -e btatt.opcode -e btatt.handle -e btatt.value

echo
echo "Method: trigger ONE vibration in the RingConn app in isolation (a manual HR/SpO2/BP"
echo "measurement completes with a buzz on Gen 3, or a Sedentary/Battery reminder). The write"
echo "that appears on handle 0x0802 at that instant is your vibrate command. Paste its bytes"
echo "into RingVibe > RingConn integration > Vibrate command (hex)."
