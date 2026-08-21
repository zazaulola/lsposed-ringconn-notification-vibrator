#!/usr/bin/env bash
# Build (if needed) and install the RingVibe module APK to the connected device.
# This only installs the app; you still enable it in LSPosed, set the scope, and reboot.
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK" ]]; then
  echo "Building debug APK…"
  JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}" \
    ./gradlew :app:assembleDebug
fi

echo "Installing $APK …"
"$ADB" install -r "$APK"

cat <<'EOF'

Installed. Next:
  1. Open LSPosed > Modules > RingVibe > enable it.
  2. In its scope, tick:  System Framework (android),  RingConn (com.gdjztech.ringconn),  RingVibe.
  3. Reboot.
  4. Open RingVibe, tap "Send test buzz now" with the RingConn app running + connected to the ring.
EOF
