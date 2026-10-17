#!/usr/bin/env bash
# OTA update for MotionWake — ships the current APK to the Fire tablets over
# the LAN, no USB cable needed for the install itself.
#
#   ./scripts/ota-update.sh                    # update all known tablets (A B C E)
#   ./scripts/ota-update.sh C                  # just C (Fire-3)
#   ./scripts/ota-update.sh 192.168.48.36      # just B
#
# HOW IT WORKS (empirically proven on this Fire build, 2026-10-15)
# -----------------------------------------------------------------
# PRIMARY — ADB over the LAN (adb install, shell uid):
#   The framework's PIS client interface is stripped on this build and its
#   file-context broker is caller-aware: `pm install` of ANY file succeeds
#   from shell/root but fails from our app uid (app_data_file,
#   media_rw_data_file and even shell_data_file all rejected for app calls).
#   So the host runs `adb install -r` (streamed install, shell uid) over the
#   adbd TCP transport (port 5555). That requires `adb tcpip 5555` to have
#   been run once — deploy.sh does it, and this script re-enables it via the
#   USB transport if the tablet rebooted in the meantime (service.adb.
#   tcp.port is non-persistent; nothing on-device can set it, so a host over
#   any live transport must run it after each reboot).
#
# FALLBACK — the app's own POST /update channels (in order):
#   pm install of a file staged in /data/local/tmp (needs the pre-created
#   666 file deploy.sh makes), PackageInstaller double-commit, pm session
#   create/write/commit, multi-location `pm install -r <path>`. These work
#   on builds where the broker is permissive — on this Fire build all fail,
#   which is why ADB is primary here.
#
# After either path: MY_PACKAGE_REPLACED restarts the service (BootStarter),
# UpdateDoneReceiver stamps /update/status "applied" for a pending attempt,
# and UpdateDoneReceiver/BOOT path re-binds a11y. Same release signing key
# on both ends (release over release), so data + prefs + tokens persist.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PKG=com.egraf.motionwake

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "No APK found — run ./scripts/build.sh first"; exit 1; }

# name|ip|usb-serial|token — the known tablets
TABLETS=(
  "A|192.168.48.187|GN41XE033142076C|2323353392f614aaeef9507762f742be"
  "B|192.168.48.36|GN41XE03314207GG|af9207f2943857162fcc9f2ec7ebbbb3"
  "C|192.168.50.175|GN41XE03314205D3|c98e7ec38a852a3fac43ec6f81a03e3f"
  "E|192.168.48.103|GN41XE03314207ER|92080024d61f1010437dc4696012bda6"
)

TARGETS=()
if [ "$#" -gt 0 ]; then
  for want in "$@"; do
    hit=""
    for t in "${TABLETS[@]}"; do
      IFS='|' read -r _name ip _serial _tok <<<"$t"
      # match by name or ip
      if [ "$ip" = "$want" ] || [ "$_name" = "$want" ]; then hit="$t"; break; fi
    done
    if [ -n "$hit" ]; then
      TARGETS+=("$hit")
    else
      echo "Unknown tablet '$want' (known: A B, or pass an IP / ip|serial|token)"
      exit 1
    fi
  done
else
  TARGETS=("${TABLETS[@]}")
fi

api() { # token ip path
  curl -s -m 20 -H "X-MotionWake-Token: $1" "http://$2:8587/$3" 2>/dev/null
}

# --- expected version of the APK on disk -----------------------------------
AAAPT="$ROOT/tools/android-sdk/build-tools/34.0.0/aapt2"
EXPECTED_NAME=""
EXPECTED_CODE=""
if [ -x "$AAAPT" ]; then
  read -r EXPECTED_NAME EXPECTED_CODE < <("$AAAPT" dump badging "$APK" 2>/dev/null \
      | awk -F'"' '/^package:/{name=$8} /^versionCode=/{code=$2} END{print name, code}')
fi
[ -n "$EXPECTED_NAME" ] || {
  EXPECTED_NAME="$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -1)"
  EXPECTED_CODE="$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' app/build.gradle | head -1)"
}
echo "APK        : $(basename "$APK")  version ${EXPECTED_NAME} (code ${EXPECTED_CODE})"
echo

# --- ensure an ADB transport to the tablet ---------------------------------
# Returns 0 if <ip>:5555 is a live "device" transport.
ensure_adb() { # ip serial
  local ip="$1" serial="$2"
  local T="$ip:5555"
  local state
  state="$(adb devices 2>/dev/null | awk -v t="$T" '$1==t{print $2}')"
  if [ "$state" = "device" ]; then
    return 0
  fi
  echo "    (adb $T not up — re-enabling TCP mode)"
  # Reboot dropped service.adb.tcp.port (non-persistent). The RESTART/tcpiop
  # sequence needs a live local transport: the USB one, if plugged in.
  if [ -n "$serial" ] && adb -s "$serial" get-state >/dev/null 2>&1; then
    adb -s "$serial" tcpip 5555 >/dev/null 2>&1 || true
    sleep 2
    local usb
    usb="$(adb devices 2>/dev/null | awk -v s="$serial" '$1==s{print $2}')"
    if [ "$usb" != "device" ]; then
      sleep 3
      adb -s "$serial" get-state >/dev/null 2>&1 || true
    fi
    state="$(adb devices 2>/dev/null | awk -v s="$serial" '$1==s{print $2}')"
    if [ "$state" != "device" ]; then
      echo "    (USB transport $serial not back either — will fall back to in-app /update)"
      return 1
    fi
  fi
  adb connect "$T" >/dev/null 2>&1 || true
  sleep 2
  state="$(adb devices 2>/dev/null | awk -v t="$T" '$1==t{print $2}')"
  if [ "$state" = "device" ]; then
    return 0
  fi
  echo "    (adb transport unavailable)"
  return 1
}

update_one() { # name ip serial tok
  local name="$1" ip="$2" serial="$3" tok="$4"
  local T="$ip:5555"
  echo "==> $name ($ip)"

  local before
  before="$(api "$tok" "$ip" "status" || true)"
  echo "    before: $(echo "$before" | head -c 160)..."

  local via_adb=0
  if ensure_adb "$ip" "$serial"; then
    echo "    installing via LAN adb ($T, shell uid)…"
    local out
    out="$(adb -s "$T" install -r "$APK" 2>&1)"
    if echo "$out" | grep -q "Success"; then
      via_adb=1
      echo "$out" | grep -v '^$' | sed 's/^/      /'
    else
      echo "      $(echo "$out" | tail -2 | tr '\n' ' ')"
      echo "      (adb install failed — falling back to in-app POST /update)"
    fi
  fi

  if [ "$via_adb" -eq 0 ]; then
    echo "    in-app self-install: POST /update ($(( $(stat -c%s "$APK") / 1024 )) KB)…"
    local up
    up="$(curl -s -m 600 -X POST -H "X-MotionWake-Token: $tok" -T "$APK" \
           "http://$ip:8587/update" 2>&1 || true)"
    echo "    commit: $(echo "$up" | head -c 300)"
    case "$up" in
      *'"ok":true'*) : ;;
      *)
        echo "    commit not ok AND no adb transport — check logcat (MotionWake tags)"
        return 1;;
    esac
  fi

  # Wait for the replaced process to come back and report the new version.
  local i st ok=""
  for i in $(seq 1 30); do
    sleep 3
    st="$(api "$tok" "$ip" "status" || true)"
    if [ -n "$st" ] && echo "$st" | grep -q '"running":true'; then
      if [ -n "$EXPECTED_NAME" ] && echo "$st" | grep -q "\"version\":\"$EXPECTED_NAME\""; then
        ok="$st"; break
      fi
    fi
  done

  local ota
  ota="$(api "$tok" "$ip" "update/status" || true)"

  echo "    after : $(echo "${ok:-<still settling>}" | head -c 160)..."
  echo "    ota   : $(echo "$ota" | head -c 200)"
  case "${ok:-}" in
    *'"running":true'*)
      case "${ok}" in
        *"\"version\":\"$EXPECTED_NAME\""*) echo "    $name OK: on ${EXPECTED_NAME} ✓" ;;
        *) echo "    $name WARN: service up but version not ${EXPECTED_NAME} yet — re-check"; return 1 ;;
      esac ;;
    *)
      echo "    $name WARN: service not reporting ok/version yet — re-check in a moment"; return 1 ;;
  esac
}

rc=0
for t in "${TARGETS[@]}"; do
  IFS='|' read -r name ip serial tok <<<"$t"
  update_one "$name" "$ip" "$serial" "$tok" || rc=1
  echo
done
exit $rc
