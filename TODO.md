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

## Long-term reliability verification — 3h capture DONE, analysis pending
- [x] 3h monitor ran 2026-10-14 13:30→16:30 local:
      `/tmp/opencode/mw-verify/` — `status.csv` (1035 rows, 10s cadence),
      `logcat-A.log` (5.5 MB) / `logcat-B.log` (1.3 MB) with per-cycle
      `MotionCamera ... diff=X.XX thr=...` (signal-strength ground truth).
      B's `showing=true` regression is visible in the rows (matches the
      keyguard bug now fixed on both tablets).
- [ ] Analyze + report:
      - quiet-noise diff distribution (logcat) vs MOTION DETECTED events;
      - every awake→asleep transition + `mLastSleepReason`;
      - propose A threshold from data (user sign-off), then POST /settings.
- [ ] Extend to overnight if the 3h window looks healthy.

## Tablet B — bring-up (done)
- [x] Install + device admin + home activity + keyguard (deploy.sh)
- [x] Camera self-heal (visible-activity) verified
- [x] Service auto-start on boot; API + API token working
- [x] Keyguard regression root-caused + fixed (see section above)
- [x] B on RELEASE build; keyguard dismissal survives reboots (2026-10-15)
- [x] B monitor is running (`running:true`), threshold 3.0, check 4s
- [ ] Decide B's dashboard view (currently same `fire-6` view as A)

## Tablet A — tuning (blocked on geometry + user decision)
- [ ] Confirm physical orientation: what does A's camera see now?
      (This morning it saw B; after rearrangement mostly static background.)
- [ ] Choose final motion threshold: A is `0.6` — first long-monitor
      minutes show detections at diff 3.2 while B stays at 1.3–1.8;
      measure real-motion vs quiet noise from `logcat-A.log`, then
      `POST /settings {"threshold": N}` (suggestion pending data).
- [ ] Re-run full E2E: idle 10 min → screen dark; motion → wake +
      dashboard reload — on both tablets.

## HA login (done for A, do for B if B is a wall)
- [x] A: logged in as `fire-1`, wall dashboard (Fire-6 view) renders.
      Some cards show red error badges (entities unavailable) — cosmetic;
      check in HA if it bothers.
- [ ] B: log in too if its dashboard is used as wall (B screen was black
      after HA-wake at 17:11 — verify `fire-6` view renders on B; if B's
      session isn't logged in, do the same adb login steps as A).

## OTA update — user request 2026-10-15 (NOT STARTED)
Goal: ship a new app build to A/B over the LAN, no USB.
- [ ] App: `POST /update` (APK body + token) → PackageInstaller
      self-install (double-commit pattern: commit(installIntent) →
      UpdateInstaller activity → commit(commitIntent)), then
      MY_PACKAGE_REPLACED revives the service (BootStarter path).
      Vendored jar already has full PackageInstaller
      (openSession/openWrite/fseal/commit(IntentSender)/abandon;
      NOTE jar MODE_FULL_INSTALL=1 — verify vs runtime).
- [ ] App: `GET /update/status` (version, last commit ts/result,
      session state), ACTION_COMMIT receiver / INITIALIZATION_COMPLETE.
- [ ] `scripts/ota-update.sh`: build if stale, POST /update to each
      tablet (A 192.168.48.187 / B 192.168.48.36 + tokens), poll
      /health → /status for the new versionName.
- [ ] Test on B first (identical-signature release→release update;
      debug→release needs uninstall+install fallback path?).
- [ ] Later: HA `button.*update` entity calling the same endpoint.

## Housekeeping
- [ ] README: document the two device quirks + deploy.sh keyguard swipe
      step (camera visible-activity gate; keyguard 10 s timeout pin)
- [x] Re-verify keyguard dismissal on EVERY wake + boot, not one-time
      (KEYGUARD section above)
- [ ] HACS repo: tag new version once behavior/docs change
      (currently v1.0.0 @ 165a0c8; new commits on main)
