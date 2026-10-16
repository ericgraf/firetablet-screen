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

echo "==> Preparing OTA staging slot (/data/local/tmp)"
# The OTA self-update's primary channel is `pm install -r` of a file under
# /data/local/tmp (shell_data_file — the one file context system_server's
# file-context broker will read; it rejects our own app_data_file). SELinux
# lets an app open+read+write an EXISTING world-writable file there but not
# CREATE one (neverallow app create on shell_data_file), so we create it once
# here, as shell, world-writable. The app truncates it during /update.
adb -s "$SERIAL" shell "mkdir -p /data/local/tmp && touch /data/local/tmp/mw-ota.apk && chmod 666 /data/local/tmp/mw-ota.apk" 2>/dev/null || true
adb -s "$SERIAL" shell "ls -l /data/local/tmp/mw-ota.apk" 2>/dev/null || true

echo "==> Enabling the LAN ADB transport (port 5555) — OTA channel"
# The OTA primary path is `adb install -r` streamed over adbd's TCP mode
# (shell uid install — the only caller the Fire PMS file-context broker
# accepts). `adb tcpip 5555` is non-persistent: after a REBOOT it is lost
# (service.adb.tcp.port is unset; only shell/adbd domains can set it), and
# ota-update.sh re-enables it via this USB transport as needed. Enable it
# once now so the LAN works immediately.
adb -s "$SERIAL" tcpip 5555 >/dev/null 2>&1 || true
sleep 2
TBL_IP="$(adb -s "$SERIAL" shell ip -4 addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\)\/.*/\1/p' | head -1)"
if [ -n "$TBL_IP" ]; then
  adb connect "$TBL_IP:5555" >/dev/null 2>&1 || true
  echo "    $TBL_IP:5555 $(adb devices 2>/dev/null | awk -v t="$TBL_IP:5555" '$1==t{print "up" " ("$2")"}' || true)"
else
  echo "    (could not resolve tablet IP — run: adb connect <ip>:5555)"
fi

echo "==> Granting runtime permissions"
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
# DEVICE_POWER is signature|privileged — used for IPowerManager.goToSleep()
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.DEVICE_POWER 2>/dev/null || true
# WRITE_SETTINGS appop — lets the app toggle screen_off_timeout to trigger sleep
adb -s "$SERIAL" shell appops set "$PKG" WRITE_SETTINGS allow 2>/dev/null || true
# SAF — Fire OS BAL exemption: lets the KeyInjector activity start from the
# foreground service (needed to inject KEYCODE_SLEEP without INJECT_EVENTS).
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null || true
adb -s "$SERIAL" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
# INJECT_EVENTS (signature) — direct InputManager key injection fallback.
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.INJECT_EVENTS 2>/dev/null || true
# AMAZON_CONTROL_KEYGUARD (signature|amazon) — lets AmazonKeyguardService
# accept our uid for the dismiss transaction (see MwA11y / ScreenController
# chain). 'amazon' protection level is declared-grantable on this build.
adb -s "$SERIAL" shell pm grant "$PKG" com.amazon.permission.AMAZON_CONTROL_KEYGUARD 2>/dev/null || true
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.CONTROL_KEYGUARD 2>/dev/null || true
# Fire OS custom permission: direct BAL exemption for the injector activity.
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.START_ACTIVITIES_FROM_BACKGROUND 2>/dev/null || true

echo "==> Setting persistence (device admin + default home)"
# Device admin: makes the package uninstall-proof (removal: dpm remove-active-admin /
# the app's own dpm.removeActiveAdmin).
adb -s "$SERIAL" shell dpm set-active-admin --user 0 "$PKG/.MwDeviceAdmin" 2>/dev/null || true
# Default home: device lands on the dashboard browser after boot.
adb -s "$SERIAL" shell cmd package set-home-activity --user 0 "$PKG/.DashboardBrowser" 2>/dev/null || true
# Nova-launcher wrapper (last-used app relaunched by Amazon's Eve at boot):
# disable it so it can't cover the dashboard home. Re-enable with:
#   adb shell pm enable com.baronkiko.launcherhijack
adb -s "$SERIAL" shell pm disable-user --user 0 com.baronkiko.launcherhijack 2>/dev/null || true

echo "==> Enabling the dismiss accessibility service"
# MotionWake's a11y service (MwA11y) is the primary keyguard-dismiss channel:
# the a11y framework dispatches its BACK/swipe with a trusted uid, bypassing
# the INJECT_EVENTS check that blocks our app uid. The setting is a
# colon-separated component list — append idempotently.
A11Y_SVC="$PKG/.MwA11y"
CUR_A11Y="$(adb -s "$SERIAL" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)"
[ "$CUR_A11Y" = "null" ] && CUR_A11Y=""
case ":$CUR_A11Y:" in
  *":$A11Y_SVC:"*)
    echo "    a11y service already enabled" ;;
  *)
    if [ -n "$CUR_A11Y" ]; then NEW_A11Y="$CUR_A11Y:$A11Y_SVC"; else NEW_A11Y="$A11Y_SVC"; fi
    adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "$NEW_A11Y" || true
    ;;
esac
A11Y_NOW="$(adb -s "$SERIAL" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true)"
echo "    enabled_accessibility_services: ${A11Y_NOW:-<empty>}"

echo "==> Dismissing the system keyguard (one-time)"
# While the keyguard is up its window pins the display's screen-off timeout
# to 10 s, and it reappears after every screen-off timeout. One genuine
# user dismissal (swipe up) makes the off-reason "user" and it stays
# dismissed across sleep cycles. requestDismissKeyguard() itself is gated
# behind Amazon's signature permission, so a real swipe is the reliable way.
adb -s "$SERIAL" shell input keyevent 224 >/dev/null 2>&1 || true
sleep 1
adb -s "$SERIAL" shell input swipe 640 750 640 100 200 >/dev/null 2>&1 || true
sleep 1
KG_STATE="$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -A13 'KeyguardServiceDelegate' | grep -E 'showing=' | head -1 | tr -d ' ')"
echo "    keyguard now: ${KG_STATE:-unknown}"

echo
echo "Installed. Useful commands ($(echo -n "am broadcast -a <ACTION> -n $PKG/.ControlReceiver" | head -c 60)...):"
echo
echo "  start monitor : adb -s $SERIAL shell am start-foreground-service -n $PKG/.MotionWakeService"
echo "  stop monitor  : adb -s $SERIAL shell am broadcast -a com.egraf.motionwake.action.STOP_SERVICE -n $PKG/.ControlReceiver"
echo "  wake screen   : adb -s $SERIAL shell am broadcast -a com.egraf.motionwake.action.WAKE -n $PKG/.ControlReceiver"
echo "  sleep screen  : adb -s $SERIAL shell am broadcast -a com.egraf.motionwake.action.SLEEP -n $PKG/.ControlReceiver"
echo "  open app      : adb -s $SERIAL shell am start -n $PKG/.MainActivity"
echo "  logs          : adb -s $SERIAL logcat | grep -E 'MotionWake|MotionCamera|ScreenControl|KeyguardSwipe|ControlReceiver'"
