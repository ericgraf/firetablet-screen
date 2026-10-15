# MotionWake TODO

## Keyguard dismissal — DONE (2026-10-15, verified on A & B)
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

## HA integration on silver — DONE (2026-10-14)
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
- [x] B monitor is running (`running:true`), threshold 3.0, check 4s
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
- [ ] HA `button.mw_*_ota` entity — FEASIBILITY CONFIRMED (2026-10-15):
      calvin (192.168.50.97, same /20 as the tablets) is the OTA
      controller (repo + adb + APK + authorized keys); HA container has
      `ssh`+`curl` (Alpine). Design: HA integration exposes
      `button.mw_<x>_ota` → `shell_command` domain:
      `ssh calvin 'cd /home/egraf/scripts/firetablet-app &&
      ./scripts/ota-update.sh <A|B>'` (HA→calvin key setup + a shell
      command config per tablet, script logs to a file HA can tail).
      Fallback if ssh keying is fiddly: tiny HTTP trigger on calvin.
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
- [ ] HACS repo: tag new version once behavior/docs change
      (currently v1.0.0 @ 165a0c8; new commits on main)
