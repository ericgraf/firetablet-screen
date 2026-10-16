# MotionWake — Fire tablet screen idle-sleep / motion-wake

Sideloadable app for Amazon Fire tablets: the tablet sleeps its screen after
**10 minutes without camera-detected motion**, and wakes the screen when motion
is detected again. Runs as a foreground service; restarts itself after boot.

Power-saving design: the camera is **not** held open continuously. Each check
cycle opens the camera for ~1–2 s, grabs a single still after auto-exposure
settles, and closes it again — then waits the check interval (default **4 s**,
configurable 2–30 s). That keeps the sensor/duty at roughly 25–40% of a
continuous preview, so the tablet stays much cooler and draws less battery.

```
app/                    Android app (com.egraf.motionwake)
  src/main/java/...     service, camera motion detector, screen controller, UI
  src/main/res/         strings + vector icons
  build.gradle          app module (AGP 8.5.2, minSdk 24, targetSdk 35)
scripts/
  setup.sh              one-time provisioning (SDK, licenses, keystore)
  build.sh              ./gradlew assembleDebug + assembleRelease
  deploy.sh             USB deploy: install, perms, admin, a11y, keyguard,
                        OTA staging slot, LAN adb transport
  ota-update.sh         LAN OTA (adb over TCP 5555 + in-app /update fallback)
config/                 signing keystore + properties (generated)
```

## Prerequisites

- JDK 17+ (21 tested)
- `adb` on PATH (tested with `/usr/bin/adb`, Ubuntu 24.04)
- Internet for the one-time SDK download (~250 MB, cached in `/tmp/opencode/dl`)

## Build

```sh
./scripts/setup.sh     # optional; build.sh runs it automatically
./scripts/build.sh
```

Outputs:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk   # signed with config/motionwake.keystore
```

Pins: AGP 8.5.2 · Gradle 8.7 · compileSdk 35 · build-tools 34.0.0 ·
androidx.core 1.13.1 (only library dependency).

## Deploy to the Fire tablet

1. Tablet: **Settings → Device → Developer Options → Debugging over USB**
   (on older firmware: AFT → "Debugging" toggle) — accept the RSA prompt.
2.

   ```sh
   ./scripts/deploy.sh                # first connected device
   # or, with several:
   ./scripts/deploy.sh <serial>
   # or over adb-over-TCP:
   adb connect <tablet-ip>:5555 && ./scripts/deploy.sh <tablet-ip>:5555
   ```

3. Grant camera (first launch): open the **MotionWake** app from the app list,
   allow the permission, then press **Start** — or kick it off straight:

   ```sh
   adb shell pm grant com.egraf.motionwake android.permission.CAMERA
   adb shell am start-foreground-service -n com.egraf.motionwake/.MotionWakeService
   ```

## OTA updates over the LAN (no USB for the install)

```sh
./scripts/ota-update.sh            # both known tablets (A + B)
./scripts/ota-update.sh B          # one of them (by name or IP)
```

Mechanism (empirical result on this Fire build): the *host* runs a streamed
`adb install -r` over adbd's TCP transport (port 5555) — a **shell-uid**
install, the only caller the PMS file-context broker here accepts (it
rejects file installs issued by the app's own uid, and the Java
PackageInstaller client interface is stripped on this build). `deploy.sh`
enables the TCP transport once; `service.adb.tcp.port` is **not
persistent**, so after the tablet reboots `ota-update.sh` re-enables it
over the still-plugged USB transport (needs USB at that moment). If no
ADB transport is available at all, the script falls back to the app's own
`POST /update` channels (pm install of a staged file in the pre-created
`/data/local/tmp` slot → PackageInstaller double-commit → pm session
create/write/commit → multi-location `pm install -r`), which work on more
permissive builds. After any install, `MY_PACKAGE_REPLACED` → BootStarter
revives the service, and `GET /update/status` is stamped `applied`
(pending-attempt + MY_PACKAGE_REPLACED close it; INITIALIZATION_COMPLETE
and SESSION_COMMITTED cover the in-app paths). `GET /status` reports
`"adb_tcp":"5555"|"", which monitoring can use to notice a transport
that dropped after a reboot.

Versioning: bump `versionCode`/`versionName` in `app/build.gradle` for
each release (reinstall accepts ≥ current; downgrades need `-d`).

## Keyguard (TV-style) dismissal — required on this build

The keyguard window belongs to uid 1000; our app uid is denied BOTH MOTION
and generic-key injection into it, and `requestDismissKeyguard()` is gated
behind an Amazon signature permission. What works: our own
`AccessibilityService` (`MwA11y`) — the a11y framework dispatches the
swipe with a trusted uid, so a `dispatchGesture` **swipe-up** dismisses it
(`performGlobalAction(BACK)` alone does NOT dismiss this TV-style
keyguard). `deploy.sh` enables the service once
(`settings put secure enabled_accessibility_services`), and the app
**re-dismisses on every wake and on boot** — the keyguard reappears after
every timeout sleep, and while it is showing it pins the display's
screen-off timeout to 10 s.

## Camera self-heal (Fire's idle-UID camera policy)

Fire restricts camera access for UIDs it considers "idle" (screen off +
no visible activity — exactly our state while asleep). When a check sees
the camera "disabled by policy", the app brings our own dashboard activity
briefly to the front so the UID has a visible activity again, then
continues (log: `self-heal: bringing dashboard to front`).

## Controlling it

| Action          | Command (adb shell)                                                                                        |
|-----------------|------------------------------------------------------------------------------------------------------------|
| Start monitor   | `am start-foreground-service -n com.egraf.motionwake/.MotionWakeService`                                   |
| Stop monitor    | `am broadcast -a com.egraf.motionwake.action.STOP_SERVICE -n com.egraf.motionwake/.ControlReceiver`        |
| Wake screen now | `am broadcast -a com.egraf.motionwake.action.WAKE -n com.egraf.motionwake/.ControlReceiver`                |
| Sleep screen    | `am broadcast -a com.egraf.motionwake.action.SLEEP -n com.egraf.motionwake/.ControlReceiver`               |
| App UI          | `am start -n com.egraf.motionwake/.MainActivity`                                                           |

> The `.ControlReceiver` component must be named in the broadcast (`-n ...`) —
> Fire OS drops *implicit* broadcasts to background apps.

The notification exposes **Wake / Sleep / Stop** actions, and the app UI shows
live status plus tunables.

## HTTP API (local REST control — for Home Assistant etc.)

The service runs a tiny local HTTP server on **port 8587** (LAN-reachable).
Every request is authenticated with the API token: send it as the
`X-MotionWake-Token` **header** or as a `?token=` **query parameter**. The
token is generated once on first start — read it in the app UI
(**MainActivity → API token**) or change it there.

```sh
TOK=<token>
BASE=http://<tablet-ip>:8587
curl -s  -H "X-MotionWake-Token: $TOK" $BASE/status
# over adb (no LAN needed):
adb forward tcp:8587 tcp:8587 && curl -s -H "X-MotionWake-Token: $TOK" http://127.0.0.1:8587/status
```

| Endpoint           | Method | Description                                                        |
|--------------------|--------|--------------------------------------------------------------------|
| `/health`          | GET    | liveness probe → `{"ok":true}` (token required)                   |
| `/status`          | GET    | full state: `running`, `screen_on`, `screen_state`, idle counters, active settings |
| `/status`          | POST   | toggle monitoring (start ↔ stop)                                   |
| `/settings`        | GET    | current tunables                                                   |
| `/settings`        | POST   | update tunables: `{"idle_minutes":n,"threshold":f,"check_seconds":n}` (each optional) |
| `/start`           | POST   | start the monitor                                                  |
| `/stop`            | POST   | stop the monitor                                                   |
| `/shutdown`        | POST   | stop monitor + stop the service process                            |
| `/wake`            | POST   | wake the screen now (waits up to ~15 s for confirmation)          |
| `/sleep`           | POST   | put the display to sleep now (waits up to ~160 s; on this Fire build the nap→screensaver→sleep pipeline is slow) |
| `/browser`         | GET    | current dashboard URL                                              |
| `/browser`         | POST   | set + open a dashboard URL: `{"url":"http://ha.local:8123/lovelace/wall"}` |
| `/browser/reload`  | POST   | reload the stored dashboard URL in the kiosk browser              |
| `/update`          | POST   | self-install a new APK (raw APK body). Channels: staged pm install\n→ PackageInstaller → pm session → multi-location pm (see **OTA** section; on this build the host-side `adb install` via\n`scripts/ota-update.sh` is the proven path) |
| `/update/status`   | GET    | OTA state: `version`, `state` (`none`/`pending`/`applied`),\n`attempt_ts`/`applied_ts`, `last_result` |
| `/update/diag`     | GET    | install-channel diagnostics (PIS surface, staged file, pm output) |

Response shape is `{"ok":true, ...}` (HTTP 200); `{"ok":false,"error":...}`
on 400/401/404/405/413. Example — put the wall tablet to sleep from Home
Assistant:

```sh
curl -s -X POST -H "X-MotionWake-Token: $TOK" $BASE/sleep
# → {"ok":true,"running":true,"screen_on":false,"screen_state":"asleep", ...}
```

`/status` fields (GET): `app`, `running`, `screen_on` (bool),
`screen_state` (`awake` | `dreaming` | `asleep`), `idle_ms`,
`idle_limit_ms`, `last_motion_age_ms`, `idle_minutes`, `threshold`,
`check_seconds`, `port`, `version`.

## Home Assistant integration (custom component, v2.0.0, 2026-10-16)

Source: `/home/egraf/scripts/ha-motionwake` (calvin); live on silver at
`/config/custom_components/motionwake` (`rsync` + `docker restart
homeassistant`, ~10 s). One config entry per tablet (host/port/token).
HA 2026.x verified — entity surface per tablet:

| Domain | Entity | Maps to |
|--------|--------|---------|
| `switch`   | `motion_monitoring` | POST `start`/`stop` (existing) |
| `button`   | `wake_display`, `sleep_display`, `reload_dashboard` | POST `/wake`, `/sleep`, `/browser/reload` |
| `button`   | `restart_service` | POST `/shutdown` (BootStarter restarts it) |
| `button`   | `update_app_ota` *(only when an OTA source is set)* | fetch APK → POST `/update` → app self-installs |
| `number`   | `threshold` (0.5–15, step .1), `check_seconds` (2–30), `idle_minutes` (1–1440) | POST `/settings` — **applied live** on the tablet |
| `text`     | `dashboard_url` | POST `/browser` — re-points the tablet + opens the URL |
| `sensor`   | `screen_state`, `last_motion_s`, `idle_time_s` | GET `/status` (existing + new) |
| `sensor`   | `app_version`, `auto_start`, `adb_tcp`, `update_state` | GET `/status` + `/settings` + `/update/status` (new) |

Also registers the admin service `motionwake.set_settings`
(`entry_id` + any of `threshold` / `check_seconds` / `idle_minutes` /
`dashboard_url`) for bulk/automation use:

```yaml
script:
  tune_tablet_c:
    sequence:
      - service: motionwake.set_settings
        data: {entry_id: <config-entry-id>, threshold: 4.0, check_seconds: 2}
```

OTA button setup: integration → **Options** → *OTA source (optional)* —
either an `http(s)://` URL that serves the release APK, or a file path
visible inside the HA container (e.g. `/config/motionwake-update.apk`).
Pressing `update_app_ota` fetches the APK, POSTs the bytes to the
tablet's `/update`, and the app installs + restarts; progress is visible
in `sensor.*_update_state`.

HA-side gotchas (2026.x): services are registered with
`helpers.service.async_register_admin_service`; number/text use the
`native_value` APIs; REST service calls are
`POST /api/services/{domain}/{service}` (slash, not dot); TextEntity
regex global flags must be at position 0 (`(?i)^...` raises
`re.PatternError` — use `^[a-z]...` or `(?i)^...` at position 0).

## Kiosk dashboard + boot behaviour

- `DashboardBrowser` is a kiosk WebView that renders the dashboard URL
  (set via the app UI or `POST /browser`) in fullscreen. `scripts/deploy.sh`
  promotes it to the default **HOME** activity
  (`cmd package set-home-activity`) and activates the app as a **device
  administrator**, so Fire keeps it across boots and the launcher screen.
  It also disables the hijack launcher package if present.
- On boot the monitor auto-restarts (Boot receiver → BootStarter
  transparent activity → foreground service, with the `CAMERA` permission
  and `WRITE_SETTINGS` appop re-asserted by `deploy.sh`), and the dashboard
  is (re)launched as the home screen.

## Tunables (app UI, or `motionwatch` SharedPreferences)

| Key             | Default | Meaning                                   |
|-----------------|---------|-------------------------------------------|
| `idle_minutes`  | `10`    | screen sleeps after this much no-motion   |
| `threshold`     | `3.0`   | min mean luma difference per check (0.5–15) — lower = more sensitive |
| `check_seconds` | `4`     | camera check cadence in seconds (2–30); larger = more power saving |
| `auto_start`    | `true`  | restart the monitor after boot            |

## How motion detection works

Camera2 rear camera (front fallback) in **duty-cycled check mode**: every
`check_seconds`, the app opens the camera at the smallest supported YUV
size, lets auto-exposure settle (~1 s), grabs one still, and closes the
camera again before the next check. Each still's luma plane is downsampled
to a 96×72 grid and compared with the previous check; motion = the mean
absolute difference ≥ `threshold` on 2 consecutive checks (the first 2
checks after start are warm-up and discarded while exposure settles).
Checking 2–30 s apart is actually *better* for detection than frame-by-frame
diffing — a scene change has more time to develop, and the static-scene
noise floor is scene-dependent — MEASURE before choosing `threshold`:
log every cycle (`cycle N diff=X.XX thr=...`) with no one around, and pick
a threshold at least ~20% above that scene's p99 while staying below the
diffs real movement produces. Our fleet measured 2026-10-16:
| Tablet | Scene profile | threshold | check |
|--------|---------------|-----------|-------|
| A (192.168.48.187) | quiet p99 ≈2.1, people 2.3–3.7+ | **1.3** | 6 s |
| B (192.168.48.36)  | noisier scene (live TV/reflections) | **1.2** | 4 s |
| C Fire-3 (192.168.50.175) | absent/standstill 0.6–2.7, person moves 10–53 | **4.0** | 2 s |
A handless test-room scene measures ~0.1–0.3. Failed checks (camera held by
another app, timeouts) pace in with a 2 s → 15 s backoff and are skipped —
they never produce a false motion event.

`dumpsys media.camera` shows the camera disconnected between checks, which
is the power-saver property to verify.

## Screen sleep / wake on Fire OS

Plain `PowerManager.goToSleep()` needs `DEVICE_POWER` (signature|privileged),
which a regular sideloaded app can't hold — on this Fire build both
`goToSleep` and `wakeUp` are present on `IPowerManager` but still
permission-gated. What actually works:

- **Sleep** — the app holds the `WRITE_SETTINGS` appop (granted by
  `deploy.sh`, or manually:
  `adb shell appops set com.egraf.motionwake WRITE_SETTINGS allow`), drops
  `Settings.System.screen_off_timeout` (Fire ships it at **10 days**!) to
  5 s, lets the framework's own bedtime timer do the actual sleep, then
  restores the original value. Binder `goToSleep()` and a KEY_SLEEP
  input-event are tried first and used as fallbacks.
- **Wake** — a `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` lock (only needs
  `WAKE_LOCK`), with binder `wakeUp()` and KEY_WAKEUP input-event as
  fallbacks.

**`pm install -r` resets appops**, so the `WRITE_SETTINGS` grant must be
re-applied after *every* install. `scripts/deploy.sh` does this
automatically; if you `adb install` by hand, re-run the `appops` command
or sleeps will fall back to the slower/less reliable paths.

## Testing hooks & logs

- Fast idle test: `am start-foreground-service -n com.egraf.motionwake/.MotionWakeService
  --el idle_minutes_override 1` (1-minute timeout; the value is written to
  prefs, so restart the service normally afterwards to get it back).
- Per-check diagnostics: logcat line `cycle N diff=X.XX thr=3.0` — use it
  to tune `threshold` against your scene (static scene ≈ 0.1–0.3, a face or
  hand easily 5–30+).
- The Fully launcher (screensaver/camera home, `de.ozerov.fully`) holds the
  front camera and a wake lock; since Fire's HAL allows one concurrent
  camera, checks may fail until Fully releases it — the app then retries
  automatically on the next cycle.

## Verify on device

```sh
adb logcat -c
adb shell am start-foreground-service -n com.egraf.motionwake/.MotionWakeService
adb logcat | grep -E "MotionWakeService|MotionCamera|ScreenControl"
# expect: "monitor started (idle=10min, threshold=3.0, check=4s)"
#        + repeated "cycle N diff=0.xx thr=3.0" (static scene) every ~4 s
# move in front of the rear camera → "MOTION DETECTED (diff=...)" and, when
# the screen is asleep, "motion while screen off — waking display"
# between checks: `adb shell dumpsys media.camera` shows us disconnected
```

## Notes

- minSdk 24 (Android 7) — covers all modern Fire tablets (tested target:
  Fire HD 8, Android 11 / API 30, model KFRAWI).
- Release APK is signed with `config/motionwake.keystore` (alias/pass
  `motionwake`) so upgrades keep app data.
- AFT packaging for the Appstore is intentionally not done yet; the APK
  sideloads fine via `adb install`.
