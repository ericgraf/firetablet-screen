# MotionWake — Fire tablet screen idle-sleep / motion-wake

Sideloadable app for Amazon Fire tablets: the tablet sleeps its screen after
**10 minutes without camera-detected motion**, and wakes the screen when motion
is detected again. Runs as a foreground service; restarts itself after boot.

```
app/                    Android app (com.egraf.motionwake)
  src/main/java/...     service, camera motion detector, screen controller, UI
  src/main/res/         strings + vector icons
  build.gradle          app module (AGP 8.5.2, minSdk 24, targetSdk 35)
scripts/
  setup.sh              one-time provisioning (SDK, licenses, keystore)
  build.sh              ./gradlew assembleDebug + assembleRelease
  deploy.sh             adb install + control commands (USB or host:port)
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

## Controlling it

| Action          | Command (adb shell)                                                            |
|-----------------|--------------------------------------------------------------------------------|
| Start monitor   | `am start-foreground-service -n com.egraf.motionwake/.MotionWakeService`      |
| Stop monitor    | `am broadcast -a com.egraf.motionwake.action.STOP_SERVICE`                     |
| Wake screen now | `am broadcast -a com.egraf.motionwake.action.WAKE`                             |
| Sleep screen    | `am broadcast -a com.egraf.motionwake.action.SLEEP`                            |
| App UI          | `am start -n com.egraf.motionwake/.MainActivity`                               |

The notification exposes **Wake / Sleep / Stop** actions, and the app UI shows
live status plus tunables.

## Tunables (app UI, or `motionwatch` SharedPreferences)

| Key            | Default | Meaning                                   |
|----------------|---------|-------------------------------------------|
| `idle_minutes` | `10`    | screen sleeps after this much no-motion   |
| `threshold`    | `3.0`   | min mean luma-frame difference (0.5–15) — lower = more sensitive |
| `auto_start`   | `true`  | restart the monitor after boot            |

## How motion detection works

Camera2 rear camera (front fallback) at the smallest supported YUV size,
frames throttled to ≤10 fps, luma plane downsampled to a 96×72 grid; motion =
mean absolute frame-to-frame difference ≥ threshold for 2 consecutive frames
(after 5 warm-up frames). Camera failures retry with 2 s → 15 s backoff.

Screen sleep uses `PowerManager.goToSleep()` (WAKE_LOCK permission) with a raw
KEY_SLEEP input-event fallback; wake uses `ACQUIRE_CAUSES_WAKEUP` full wake
lock with a KEY_WAKEUP fallback.

## Verify on device

```sh
adb logcat -c
adb shell am start-foreground-service -n com.egraf.motionwake/.MotionWakeService
adb logcat | grep -E "MotionWakeService|MotionCamera|ScreenControl"
# expect: "monitor started (idle=10min, threshold=3.0)" + "camera ready (WxH)"
# wave something in front of the camera → "motion ..." lines appear when screen is off
```

## Notes

- minSdk 24 (Android 7) — covers all modern Fire tablets (tested target:
  Fire HD 8, Android 11 / API 30, model KFRAWI).
- Release APK is signed with `config/motionwake.keystore` (alias/pass
  `motionwake`) so upgrades keep app data.
- AFT packaging for the Appstore is intentionally not done yet; the APK
  sideloads fine via `adb install`.
