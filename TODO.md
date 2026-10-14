# MotionWake TODO

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

## Long-term reliability verification  ← RUNNING (user: motion + screen-off seem unreliable)
- [x] 3h monitor STARTED 2026-10-14 17:30 local (3h → ~20:30):
      `/tmp/opencode/mw-verify/monitor.sh 10800`
      - `status.csv`: every 10s/tablet epoch,state,screen_on,running,
        idle_ms,last_motion_ms,sleep_reason,keyguard showing,timeout
      - `logcat-A.log` / `logcat-B.log`: app tags incl. per-cycle
        `MotionCamera ... diff=X.XX thr=...` (signal-strength ground truth)
      - pids: `monitor.pid`, `logcat-A.pid`, `logcat-B.pid`
- [ ] Analyze + report:
      - quiet noise diff distribution (logcat) vs MOTION DETECTED events;
      - every awake→asleep transition + `mLastSleepReason` (timeout=MotionWake
        trick vs power_button=user vs gesture) + keyguard showing at the same
        epoch (keyguard 10s pin correlation);
      - camera-path wake: motion event while asleep → `screen_on=true`
        within 1–2 check cycles, no API /wake.
- [ ] Early signal from first minutes: A (thr 0.6) fires on diff 3.2;
      B (thr 3.0) sits at diff 1.3–1.8 with zero false detections.
- [ ] If A over-fires: raise threshold 0.6 → 1.5–2× the observed quiet
      max (stay below ~2.3 real-person minimum), `POST /settings`,
      30-min retest. Awaiting user sign-off on final value.
- [ ] Extend to overnight if the 3h window looks healthy.

## Tablet B — finish bring-up (mostly done)
- [x] Install + device admin + home activity + keyguard dismissal (deploy.sh)
- [x] Camera self-heal (visible-activity) verified
- [x] Service auto-start on boot; API + API token working
- [x] Observed 2026-10-14 17:11: keyguard re-appeared (`showing=true`,
      10s timeout pin) after a sleep cycle → re-swiped (deploy.sh pattern),
      sticky again, `screen_off_timeout` restored to `864000000`.
      → NOTE: B's keyguard sticky state can regress after sleep cycles;
      candidate fix: re-dismiss keyguard once on every wake path AND on
      boot (not one-time). Track in long-term monitor.
- [ ] Decide B's dashboard view (currently same `fire-6` view as A)
- [ ] Re-deploy RELEASE apk on B (currently debug for run-as convenience)
- [ ] Confirm keyguard dismissal survives a reboot on B

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

## Housekeeping
- [ ] README: document the two device quirks + deploy.sh keyguard swipe
      step (camera visible-activity gate; keyguard 10 s timeout pin)
- [ ] Re-verify keyguard dismissal on EVERY wake + boot, not one-time
      (see B regression above) — code change in MotionWakeService
- [ ] HACS repo: tag new version once behavior/docs change
      (currently v1.0.0 @ 165a0c8; new commits on main)
