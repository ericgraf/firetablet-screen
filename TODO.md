# MotionWake TODO

## Install the HA integration on silver  ← NEW
- [ ] Install the `motionwake` HACS integration into Home Assistant on silver
      (`192.168.50.109`, Docker container `homeassistant`).
- [ ] Use the codex skill for HA access: `~/.codex/skills/home-assistant/SKILL.md`
      (SSH to silver, `docker exec homeassistant`, config at `/config`,
      REST API inside container at `http://127.0.0.1:8123`, bearer-token
      pattern from an active auth record — read the skill before touching
      auth storage).
- [ ] Source: HACS repo `ericgraf/ha-motionwake` (v1.0.0, `@165a0c8`), or
      drop `/home/egraf/scripts/ha-motionwake/custom_components/motionwake` into
      `/config/custom_components/` directly + restart HA, then
      `POST /api/config/core/check_config` and reload.
- [ ] Add the integration for both tablets:
      A = `GN41XE033142076C` (IP: `adb shell ip addr`, port 8587, token from
      `shared_prefs/motionwatch.xml` — A: read via run-as/debug or logs;
      B token known: `af9207f2943857162fcc9f2ec7ebbbb3`).
- [ ] Verify: coordinator polls `/status`, switch/button/sensor entities
      show up and drive the tablets (wake/sleep/reload).

## Tablet B — finish bring-up (mostly done)
- [x] Install + device admin + home activity + keyguard dismissal (deploy.sh)
- [x] Camera self-heal (visible-activity) verified
- [x] Service auto-start on boot; API + API token working
- [ ] Decide B's dashboard view (currently `http://silver:8123/wall-tablets/fire-6`,
      same as A) and set per device if different
- [ ] Re-deploy RELEASE apk on B (currently debug for run-as convenience)
- [ ] Confirm keyguard dismissal survives a reboot (expected: sticky
      "offReason=USER" state, as on A)

## Tablet A — tuning (blocked on geometry + user decision)
- [ ] Confirm physical orientation: can A's camera see B and the room?
      (This morning it did; after today's rearrangement both cameras see
      mostly static background — diffs 0.5–0.9 on both.)
- [ ] Choose final motion threshold: current A setting `0.6` sits at the
      bottom of noise (spikes to ~1.9); B is `3.0`. Measure a real motion
      (wave a hand) and static noise, pick a value with headroom,
      `POST /settings {"threshold": N}`.
- [ ] Re-run full E2E: idle 10 min → screen dark; motion → wake +
      dashboard reload — on both tablets.

## HA login (done for A, do for B if B is a wall)
- [x] A: logged in as `fire-1`, wall dashboard (Fire-6 view) renders.
      Some cards show red error badges (entities unavailable) — cosmetic,
      check in HA if it bothers.
- [ ] B: log in too if its dashboard is used (same credentials) — or
      point B at a per-device HA view that's already logged in? (HA
      sessions are per-WebView-storage, so each tablet logs in itself.)

## Housekeeping
- [ ] README: document the two discovered quirks + deploy.sh keyguard
      swipe step (camera visible-activity gate; keyguard 10 s timeout pin)
- [ ] Consider: `requestDismissKeyguard` is gated behind Amazon's
      `ENABLE_KEYGUARD_FLAGS` (signature|amazon) — a `KEYGUARD_DISABLE_*`
      DPM path needs us to be profile owner; revisit if keyguard flakes
- [ ] HACS repo: tag new version once client/behavior docs change
