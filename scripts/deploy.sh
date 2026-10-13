#!/usr/bin/env bash
# Deploy to a Fire tablet over USB (or adb-over-TCP: ./scripts/deploy.sh <host>:5555).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PKG=com.egraf.motionwake
DEVICE="${1:-}"

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "No APK found — run ./scripts/build.sh first"; exit 1; }

if [ -n "$DEVICE" ]; then
  adb connect "$DEVICE" >/dev/null || true
fi

if [ -z "$DEVICE" ]; then
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device"{print $1}')
else
  mapfile -t DEVICES < <(adb devices | awk -v d="$DEVICE" 'NR>1 && $2=="device" && $1==d {print $1}')
fi

if [ "${#DEVICES[@]}" -eq 0 ]; then
  echo "No connected adb device."
  echo "  USB: plug in, enable debugging, accept the RSA prompt."
  echo "  wifi: adb connect <tablet-ip>:5555   (then: $0 <tablet-ip>:5555)"
  exit 1
fi
if [ "${#DEVICES[@]}" -gt 1 ] && [ -z "$DEVICE" ]; then
  echo "Multiple devices — pass one as argument:"
  printf '  %s\n' "${DEVICES[@]}"
  exit 1
fi
SERIAL="${DEVICES[0]}"

echo "==> Installing $APK → $SERIAL"
adb -s "$SERIAL" install -r "$APK"

echo "==> Granting runtime permissions"
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
# DEVICE_POWER is signature|privileged — used for IPowerManager.goToSleep()
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.DEVICE_POWER 2>/dev/null || true
# WRITE_SETTINGS appop — lets the app toggle screen_off_timeout to trigger sleep
adb -s "$SERIAL" shell appops set "$PKG" WRITE_SETTINGS allow 2>/dev/null || true

echo
echo "Installed. Useful commands ($(echo -n "am broadcast -a <ACTION> -n $PKG/.ControlReceiver" | head -c 60)...):"
echo
echo "  start monitor : adb -s $SERIAL shell am start-foreground-service -n $PKG/.MotionWakeService"
echo "  stop monitor  : adb -s $SERIAL shell am broadcast -a com.egraf.motionwake.action.STOP_SERVICE -n $PKG/.ControlReceiver"
echo "  wake screen   : adb -s $SERIAL shell am broadcast -a com.egraf.motionwake.action.WAKE -n $PKG/.ControlReceiver"
echo "  sleep screen  : adb -s $SERIAL shell am broadcast -a com.egraf.motionwake.action.SLEEP -n $PKG/.ControlReceiver"
echo "  open app      : adb -s $SERIAL shell am start -n $PKG/.MainActivity"
echo "  logs          : adb -s $SERIAL logcat | grep -E 'MotionWake|MotionCamera|ScreenControl|ControlReceiver'"
