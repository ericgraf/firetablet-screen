# MotionWake TODO

## Fleet status (as of 2026-10-17)

| Tablet | Serial | IP | App | threshold | check | confirm | idle | Dashboard |
|--------|--------|----|-----|-----------|-------|---------|------|-----------|
| A (Fire-1) | `GN41XE033142076C` | 192.168.48.187 | 2.7/18 | **1.5** | 2 s | 2 | 10 min | `http://silver:8123/wall-tablets/fire-6` (as `fire-1`) |
| B (Fire-2) | `GN41XE03314207GG` | 192.168.48.36 | 2.7/18 | **0.5** | 2 s | 2 | 10 min | `http://silver:8123/wall-tablets/fire-6` (as `fire-1`) |
| C (Fire-3) | `GN41XE03314205D3` | 192.168.50.175 | 2.7/18 | **1.0** | 2 s | 1 | 10 min | `http://silver:8123/wall-tablets/fire-3` (as `fire-3`) |
| D | `GCC1XE0323250SU3` | 192.168.50.99 | 2.7/18 | **1.5** | 2 s | 2 | 10 min | `http://silver:8123/wall-tablets/fire-6` |
| E | `GN41XE03314207ER` | 192.168.48.103 | 2.7/18 | **1.5** | 2 s | 2 | 10 min | `http://silver:8123/wall-tablets/fire-6` |

API tokens: A `2323353392f614aaeef9507762f742be`; B `af9207f2943857162fcc9f2ec7ebbbb3`;
C `c98e7ec38a852a3fac43ec6f81a03e3f`;
D `ed725e13227982b69acd2d72258990cd`;
E `92080024d61f1010437dc4696012bda6`.
HA: config entries live for A, B, C (C entry_id `G4EUGVQJJU93U8SDKTRCMAD6C5`,
unique_id `192.168.50.175:8587`), D (entry_id `755DE7BBD633493AB0DA38`,
unique_id `192.168.50.99:8587`) and E (entry_id `AFB1C0ADE99E4B99B33E29`,
unique_id `192.168.48.103:8587`). HA local user for C:
`fire-3` / `firewall3` (asked user to change the password later in HA).

## Tablet D — onboarded DONE (2026-10-17)
- [x] Fully kiosk (`de.ozerov.fully`) admin toggled off by the user via
      Settings → Device admin apps (the only working removal path on these
      Fire builds), then `pm uninstall de.ozerov.fully` succeeded.
- [x] `./scripts/deploy.sh GCC1XE0323250SU3` (USB): v2.7/18, OTA slot, LAN
      adb 192.168.50.99:5555, perms, MwDeviceAdmin, home=DashboardBrowser,
      MwA11y, keyguard dismissed.
- [x] Keyboard fixed: `com.amazon.redstone` was in per-user-disabled PMS
      state; `pm enable com.amazon.redstone` + `ime enable/set
      com.amazon.redstone/.FireKeyboardService` (same fix as E).
- [x] `pm grant CAMERA`; monitor running; healthy cycles (standstill
      0.12–0.14 vs thr 1.5); A-profile settings applied (1.5/2 s/confirm
      2/idle 10/auto_start) + fire-6 dashboard URL; token generated.
- [x] `ota-update.sh` line added (D|192.168.50.99|GCC1XE0323250SU3|token);
      HA config entry added on silver (`755DE7BBD633493AB0DA38`) + HA
      restarted — 18 entities live (incl. `number..._confirm_cycles`).
- [ ] Calibration pass: D is the quietest scene in the fleet
      (standstill 0.12–0.14); thr 1.5 is ~10× the noise floor — room to
      detect earlier if wanted.

## Tablet E — onboarded DONE (2026-10-16)
- [x] Fully kiosk (`de.ozerov.fully`) admin disabled by user via
      Settings → Device admin apps (the only working removal path on this
      build — `pm uninstall` blocked by PMS while the admin record exists,
      `dpm remove-active-admin` a silent no-op since `getActiveAdmins(0)`
      returns an empty list), then `pm uninstall de.ozerov.fully` succeeded.
- [x] `./scripts/deploy.sh GN41XE03314207ER` (USB): v2.7/18, OTA slot, LAN adb
      192.168.48.103:5555, perms, MwDeviceAdmin, home=DashboardBrowser,
      MwA11y, keyguard dismissed.
- [x] Keyboard fixed: `com.amazon.redstone` (FireKeyboardService) was in
      per-user-disabled PMS state; `pm enable com.amazon.redstone` +
      `ime enable/set com.amazon.redstone/.FireKeyboardService` (was on a
      third-party default IME). Same fix on D.
- [x] `pm grant CAMERA`; monitor running; healthy cycles (diff 0.23–0.35 vs
      thr 1.5); A-profile settings applied (1.5/2 s/confirm 2/idle 10/
      auto_start) + fire-6 dashboard URL; token read from app UI via
      uiautomator (prefs not world-readable).
- [x] `ota-update.sh` line added (E|192.168.48.103|GN41XE03314207ER|token);
      HA config entry added on silver + HA restarted — 18 entities live
      (incl. the new `number..._confirm_cycles` from integration v2.0.1).
- [ ] Calibration pass: E is a quiet scene (standstill 0.23–0.35), threshold
      1.5 is 4–6× the noise floor — room to tune per scene.

## Tablet C (Fire-3) — onboarded DONE (2026-10-16)
- [x] Kiosk identified (Fully kiosk `de.ozerov.fully` + `com.baronkiko.launcherhijack`,
      showing the HA `fire-3` view) and BOTH fully uninstalled
      (`pm uninstall -k --user 0`). Active admins now: only
      `com.amazon.parentalcontrols` (profile owner, nominal) +
      `com.egraf.motionwake/.MwDeviceAdmin`.
- [x] `./scripts/deploy.sh GN41XE03314205D3` (over USB): v2.6/17, OTA slot,
      LAN adb 192.168.50.175:5555, perms, MwDeviceAdmin, home=DashboardBrowser,
      MwA11y, keyguard dismissed; clean reboot + boot chain verified.
- [x] `pm grant CAMERA` needed explicitly on C; token generated,
      restart-persistence proven (force-stop + reviver broadcast).
- [x] HA: config entry added for C + HA restarted; 6 entities registered.
- [x] HA login on C (adb UI automation): username (621,338) / password
      (621,450) / login (621,604) in 1280x800 landscape; TAB/ENTER over
      BACK; `fire-3` logged in, dashboard renders (sidebar + Fire-1/2/3 views).
- [x] Calibration (capture in /tmp/opencode/calC-raw.txt): absent/standstill
      0.6–2.7, person moves 10–53 (peak 53.77) → **threshold 4.0, check 2 s**
      (live via POST /settings; worst-case wake latency ~6.6 s).
- [x] C quirks logged: explicit `pm grant CAMERA`; `wm size` 800x1280 but
      screencap PNG is 1280x800 (input coords = bitmap space); uiautomator
      mostly "null root node"; shell "Permission denied" on
      `/data/data/<pkg>/`.

## v2.7 build (code 18) — AWAITING USER GO (2026-10-16)
- [ ] **AlarmManager watchdog for A's dead-process incident:** 2026-10-16
      ~10:34 a gms crash-loop killed A's motionwake service; START_STICKY
      restarted once but was lost (A's RAM ~1.76/1.86 GB used — low-memory
      killer territory). Build 2.7: schedule an AlarmManager exact-ish
      wake/heartbeat that re-asserts the foreground service when it's dead
      (the app can't survive its own process death otherwise).
- [ ] **`confirm_cycles` runtime setting:** motion gate is currently the
      compile-time `CONFIRM_CYCLES = 2` (MotionCameraManager L65). Expose as
      a POST /settings key (1–5) + HA number entity so the 2-consecutive
      crossing gate is tunable per tablet without a rebuild.
- [ ] Note: user was concurrently experimenting in MotionCameraManager
      (a `motionFlag` AtomicBoolean, an `if (threshold > 0.05f)` guard
      around detection, a duplicated "Motion state: none -> ACTIVE" log
      line); the working tree is back to clean @1b23325 (2026-10-16
      evening) — revisit whether those changes are still wanted before
      building 2.7.
- Also proposed (needs GO): `auto_start` accepted by POST /settings
  (currently read-only in the API) so the HA `auto_start` sensor can
  become a real switch.

## HA integration v2.0.0 — DONE (2026-10-16)
- [x] Full configuration surface in Home Assistant (replaces
      "can these values be updated over the air from HA?" — yes):
      `number.*_{threshold,check_seconds,idle_minutes}` (applied live),
      `text.*_dashboard_url` (sets + opens),
      `button.*_{restart_service,update_app_ota}`,
      `sensor.*_{idle_time_s,app_version,auto_start,adb_tcp,update_state}`,
      + `motionwake.set_settings` service (entry_id + any fields).
      See README "Home Assistant integration" section.
- [x] E2E verified: `number.set_value` threshold 4.0→4.3 applied on C
      (app `/settings` confirms 4.300000190734863), restored to 4.0 via
      the `motionwake.set_settings` service; all 48 entities (16 × 3
      tablets) in the state machine with live values.
- [x] HA 2026.10.1 API fixes: `async_register_admin_service(hass, domain,
      name, func, schema)`; `NumberEntity` native API; `TextEntity
      .native_value` + `TextMode.TEXT`; no `BooleanSensor` (use Sensor
      "on"/"off"); `CONF_ENTRY_ID` not in `homeassistant.const` (define in
      const.py).
- [x] HA REST gotcha (2026.x): service calls are
      `POST /api/services/{domain}/{service}` (dot form 404s).
      Bearer = HS256 JWT `{"iss": <refresh_token.id>}` signed with that
      token's `jwt_key`.
- [ ] OTA button wiring: set each entry's Options → *OTA source* to the
      release APK (URL or /config path) — then `button.*_update_app_ota`
      appears. (Alternative calvin-ssh `ota-update.sh` design still valid
      as fallback.)

## Keyguard dismissal — DONE (2026-10-15, verified on A & B & C)
Root cause (empirical, this Fire build):
- The keyguard window belongs to uid 1000; our app uid 10121 is denied
  BOTH MOTION and generic-KEY injection into it:
  `SecurityException: Injecting to another application requires
  INJECT_EVENTS`. Shell uid (2000) passes the same injections (why
  `adb shell input swipe/keyevent` works).
- `AmazonKeyguardService` (binder) has a dismiss transaction (service
  call code 5, verified from shell: showing→false, sticky, timeout
  restored), but from our uid it demands `android.permission.CONTROL_KEYGUARD`
  or `com.amazon.permission.AMAZON_CONTROL_KEYGUARD`
  (prot=`signature|amazon`; `pm grant` → "not a changeable permission
  type"). `amazon_input`/`amazon_keyevent` binders need similar
  Amazon keyevent permissions. `su` does not exist.
- **Solution: our own AccessibilityService (`MwA11y`).** The a11y
  framework dispatches gestures/keys with a trusted uid — no
  INJECT_EVENTS check. `dispatchGesture(swipe-up)` (the proven
  dismiss gesture) dismisses the keyguard from our process.
  `performGlobalAction(BACK)` alone does NOT dismiss this TV-style
  keyguard (swipe is what works).
- Gotchas found:
  - Fire's AccessibilityManagerService "Ignoring non-encryption-aware
    service" at boot → `android:directBootAware="true"` required.
  - Enable once at deploy: `settings put secure
    enabled_accessibility_services com.egraf.motionwake/.MwA11y`
    (deploy.sh; the value can read back as literal `null` when unset).
  - At boot the keyguard window is not input-ready yet — a dismiss
    attempt right after boot no-ops; the first real WAKE dismisses.
  - The keyguard re-appears after every timeout sleep (not sticky-USER
    via swipe) — so the chain re-dismisses on EVERY wake path + boot:
    ScreenController.dismissKeyguardSwipe() orders: AmazonKeyguardService
    probe → a11y BACK → a11y swipe (effective) → KEY_BACK(111) →
    MOTION swipe → `input swipe`.
- Verified: B reboot→showing=true→WAKE path→`keyguard dismissed via a11y
  swipe gesture`, timeout 864000000, /sleep+/wake cycle stable; A reboot
  clean (showing=false, Awake, a11y bound).
- Vendored-jar note: `KeyEvent.KEYCODE_BACK` == 4 in tools/android-sdk/
  platforms/android-35/android.jar (device uses 111) — use literal
  keycodes for dismiss keys.

## HA integration on silver — DONE (2026-10-14; v2.0.0 full control 2026-10-16)
- [x] v2.0.0 (2026-10-16): full configuration surface — see the
      "HA integration v2.0.0" section above (numbers/text/buttons/sensors
      + `motionwake.set_settings` service + OTA button), E2E tested.
- [x] Installed in `/config/custom_components/motionwake`
      (copied from `/home/egraf/scripts/ha-motionwake`; HACS repo
      `ericgraf/ha-motionwake` available later for updates).
- [x] Config flows completed for BOTH tablets via REST API.
      HA-build quirks learned (for future installs):
      - manifest MUST have a `"version"` key or HA silently blocks loading;
      - new routes: `POST /api/config/config_entries/flow` +
        `POST /api/config/config_entries/flow/{flow_id}` (old
        `entry_flow/init` is gone);
      - Bearer token: sign `{"iss": <refresh_token.id>, "iat", "exp"}`
        HS256 with the token's `jwt_key` **hex string as UTF-8 bytes**
        (NOT hex-decoded) from `/config/.storage/auth`; use an admin-group
        user token or flow views 401.
- [x] 12 entities live (6 per tablet): `switch.*_motion_monitoring`,
      `button.*_{wake,sleep,reload}`, `sensor.*_{screen_state,last_motion_s}`,
      devices `MotionWake <ip>` registered.
- [x] E2E: HA `button.*wake_display` on B → asleep→awake within seconds.
- [x] Integration code fixed for recent HA (HA 2026.x):
      `update_interval` = timedelta, coordinator gets `config_entry`,
      entities carry `config_entry` + `unique_id` explicitly.

## Long-term reliability verification — DONE (analysis 2026-10-15)
- [x] Monitor ran 2026-10-14 13:30→16:30 (A's logcat kept capturing to
      00:11 the next day — overnight window effectively covered):
      `/tmp/opencode/mw-verify/` — `status.csv` (1035 rows), `logcat-A.log`
      (3505 cycles), `logcat-B.log` (6187 cycles), per-cycle `diff=` truth.
- [x] Analysis (per-cycle diff distributions, time-split busy/quiet, state
      transitions, keyguard):
  - **A stayed AWAKE the entire 3h window (517/517 rows, zero sleep cycles).**
    Root cause: thr 0.6 is *below A's own noise peak*. Split by activity:
    - A quiet (15:00→00:11, no one present, n=2762): p50 0.53, p90 0.90,
      p95 1.11, **p99 2.10**; ≥2.0 in 0.9% of cycles; one 15.3 outlier.
    - A busy (13:30–15:00, people present, n=743): p90 2.99; **34% of
      cycles ≥2.0**, 10% ≥3.0, max 3.7.
    - → at thr 0.6, **34% of QUIET cycles cross** (946/2762) → false
      detections keep resetting idle → A never reaches the 10-min idle
      sleep. This is the "screen won't turn off / seems unreliable"
      complaint. (A is awake for legit reasons during 13:30–15:00 too —
      real people.)
  - Crossing rates on A's quiet profile (9h): ≥1.0 → 6.9%, ≥2.0 → 1.1%,
    **≥2.5 → 0.5%** (14 cycles in 9h, scattered; CONFIRM_CYCLES=2 rejects
    the isolated ones), ≥3.0 → 0.2%.
  - **B (thr 3.0): 26 wake/sleep cycles, all short (20–100 s).** B's scene
    is noisier (quiet p95 2.66, p99 4.56 — sustained tail, likely a live
    TV/reflections in view); 3.0 sits just above B's quiet p95, reasonable;
    wakes plausibly real. Recheck by eye / adjust if false.
  - **Keyguard regression confirmed in-window**: B `showing=true` in all
    517 rows (old bug); A clean. Fixed in shipped builds (dismiss chain on
    every wake + boot), re-verified across reboots of 2.5/2.6 deploys.
- [ ] **PROPOSED A threshold: 2.5** (user sign-off, then `POST /settings
      {"threshold": 2.5}`). Rationale: +19% headroom over A quiet p99
      (2.10), ~8% below the person floor (2.3) — false-wake crossings drop
      from 34% of cycles (at 0.6) to 0.5%, while every person crossing
      (2.3–3.7, many consecutive cycles) still detects. Alternatives if
      still unsatisfied: 2.2 more sensitive (0.8%) / 2.8 more conservative
      (0.4%). B stays 3.0.
- [x] Overnight extension: A's capture ran to 00:11 next day with the same
      quiet profile — no separate overnight run needed.

## Tablet B — bring-up (done)
- [x] Install + device admin + home activity + keyguard (deploy.sh)
- [x] Camera self-heal (visible-activity) verified
- [x] Service auto-start on boot; API + API token working
- [x] Keyguard regression root-caused + fixed (see section above)
- [x] B on RELEASE build; keyguard dismissal survives reboots (2026-10-15)
- [x] B monitor is running (`running:true`), **threshold 1.2**, check 4s
      (re-tuned 2026-10-16; was 3.0 during bring-up)
- [ ] Decide B's dashboard view (currently same `fire-6` view as A)

## Tablet A — tuning (blocked on user sign-off)
- [x] Confirm physical orientation: A saw the busy period (13:30–15:00,
      people crossing, diffs to 3.7) then a calm ~9h night — view is good.
- [x] **A threshold = 2.5 — SIGNED OFF + APPLIED 2026-10-15** (data: A quiet
      p99 2.10, person floor 2.3 — see Long-term verification section).
      Applied via `POST /settings {"threshold": 2.5}`; `/status` confirms
      `threshold:2.5`. Expected: A now actually reaches its 10-min idle
      sleep (was awake 100% of the 3h window at 0.6), spurious wakes drop
      34%→0.5% of cycles. Watch one evening; fallbacks if needed:
      2.2 (1.4% cross) / 2.8 (0.4% cross). B stays 3.0.
- [x] Re-run E2E after the threshold change (done 2026-10-15 16:10):
      A reached its **10-min idle → ASLEEP** for the first time since the
      3h window (asleep, keyguard `showing=false`, no false wakes across
      the idle — at thr 0.6 A was awake 100% of that window); then
      `/wake` → awake, keyguard dismissed on wake, `fire-6` dashboard
      verified by screenshot. Motion→wake side covered by B's 26 real
      wake/sleep cycles during the monitor. One-evening watch still
      recommended before considering it fully settled.
- [x] **A re-tuned to 1.3 / check 6 s (2026-10-16)** — current live value
      (fallbacks if 1.3 misbehaves: 1.4 more conservative / 1.2 more
      sensitive); B to 1.2 / 4 s. Both still need the one-evening watch.
- [ ] A dead-process incident 2026-10-16 ~10:34 (gms crash-loop + low
      RAM killed the service; START_STICKY restart lost once) →
      watchdog in v2.7 (see v2.7 section above).

## HA login (done for A, do for B if B is a wall)
- [x] A: logged in as `fire-1`, wall dashboard (Fire-6 view) renders.
      Some cards show red error badges (entities unavailable) — cosmetic;
      check in HA if it bothers.
- [x] B: logged in too if its dashboard is used as wall (B screen was black
      after HA-wake at 17:11 — verify `fire-6` view renders on B; if B's
      session isn't logged in, do the same adb login steps as A).
      - [x] VERIFIED 2026-10-15 16:05: B wakes on demand (keyguard
            dismissed on wake, `showing=false`), HA session alive as
            `fire-1`, `fire-6` view renders full dashboard. Both tablets
            point at `http://silver:8123/wall-tablets/fire-6`.

## OTA update — DONE (2026-10-15, v2.6/code 17 on A & B, verified over LAN)
Goal: ship a new build to A/B over the LAN, no USB. ✅ Shipped.

### Mechanism (final)
- **PRIMARY = host-driven `adb install -r` (streamed, shell uid) over adbd
  TCP 5555.** `scripts/ota-update.sh <A|B|ip>`: ensures the TCP transport
  (auto re-enables it via `adb -s <usb-serial> tcpip 5555` after a reboot),
  installs, polls `/status` until the new `version` is reported.
- **In-app `POST /update` channels** (for permissive builds / future), order:
  `pm install -r` of a pre-created 666 file under `/data/local/tmp` → PIS
  double-commit (PisBridge) → pm session create/write/commit →
  multi-location `pm install -r <path>`.
- UpdateDoneReceiver stamps `/update/status` applied on
  SESSION_COMMITTED / INITIALIZATION_COMPLETE, and (new) on
  MY_PACKAGE_REPLACED when an in-app attempt is pending — this is what
  closes host-driven installs (they don't fire INITIALIZATION_COMPLETE).

### Fire-build gotchas (empirical, 2026-10-15)
- PIS client side dead here: `getSystemService("package_installer")` null;
  `IPackageInstaller` stripped to `uninstall` only (classic Java API unusable).
- pm session cmds from app uid: `install-create` OK (needs `--user 0`;
  missing `-i com.egraf.motionwake` → NPE in AppOpsService);
  `install-write` stdin → "Reverse mode only supported from shell or system";
  PATH → server opens the file → file-context broker.
- **File-context broker is caller-aware:** app_data_file, media_rw_data_file
  AND shell_data_file all rejected for app-uid callers; shell uid passes.
  (Amazon `fileSystem` service + AOSP PackageManagerShellCommand#setParamsSize.)
- SELinux: `appdomain→shell_data_file (write getattr)`,
  `untrusted_app_all→(open read …)`, `neverallow app create` → the app may
  truncate+write an EXISTING 666 file in /data/local/tmp but not create one
  (deploy.sh pre-creates it).
- `service.adb.tcp.port` is NOT persistent and only shell/adbd domains can
  set it → after every reboot a host must run `adb tcpip 5555` over any live
  transport (USB); `/status` now reports this as `"adb_tcp":"5555"|""` for
  monitoring. Nothing on-device in our app's uid can set it (shell_prop).
- `adb install -r` (reinstall) accepts equal/lower versionCode only with `-d`;
  we bump per OTA step as a marker anyway.

### Verification (2026-10-15)
- B (192.168.48.36): 12→13→14→15→16→17 over the session; rebooted
  mid-session (clean boot: service, a11y, dashboard intact; TCP mode dropped
  as expected, ota-update.sh auto re-enabled via USB and the OTA succeeded).
- A (192.168.48.187): 1 → 15 (USB bootstrap) → 16 → 17 over the LAN.
- Both end on 2.6/17: monitor running, a11y bound, admin active,
  keyguard dismissed, `adb_tcp":"5555"`, `/update/status` stamped
  `applied` after B's host-driven install (new receiver logic ✓).
- Code: 98fd304 (main, pushed).

### Follow-ups
- [x] HA OTA button — DONE via the in-app channel (2026-10-16): integration
      v2.0.0 `button.*_update_app_ota` fetches the APK from the entry's
      Options→*OTA source* (URL or /config file path) and POSTs the bytes to
      the tablet's `/update` (app stages + installs + restarts; progress in
      `sensor.*_update_state`). The calvin-ssh `ota-update.sh` design below
      remains as an alternative (still used for host-driven installs).
- [ ] Nice-to-have: app self-heals adbd-TCP after reboots (speak ADB
      protocol to its own adbd over 127.0.0.1:5555 with a staged host RSA
      key) — removes the one post-reboot USB touch.

## Housekeeping
- [x] README: document the two device quirks + deploy.sh keyguard swipe
      step (camera visible-activity gate; keyguard 10 s timeout pin)
      — done 2026-10-15 (new README sections: Keyguard dismissal,
      Camera self-heal, OTA updates over LAN; threshold-tuning guidance).
- [x] Re-verify keyguard dismissal on EVERY wake + boot, not one-time
      (KEYGUARD section above)
- [x] `scripts/deploy.sh` launcher package name typo fixed:
      `launcherhijacl` → `launcherhijack` (2026-10-16)
- [x] README fleet table (A/B/C profiles + final thresholds) + HA 2.0.0
      integration section (2026-10-16)
- [ ] HACS repo: tag new version once behavior/docs change
      (app currently 2.6/17 @ main; HA integration v2.0.0 untagged)
