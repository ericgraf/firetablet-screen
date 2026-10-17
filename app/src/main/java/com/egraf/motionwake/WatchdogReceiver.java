/*
 * WatchdogReceiver — self-revival for the monitor service.
 *
 * MotionWakeService arms a repeating inexact AlarmManager alarm while
 * monitoring, and stamps PREF_HEARTBEAT_TS every ~5 s from its tick loop.
 * This manifest-registered receiver fires on that alarm; if our process
 * died (crash, low-memory kill, Play-services loop taking us down with it,
 * see the Fire-3 gms incident) the heartbeat is stale on disk and we
 * revive the service through the proven BootStarter activity path.
 *
 * Cases:
 *   - service alive:             heartbeat fresh  -> no-op
 *   - monitoring intentionally
 *     stopped:                   armed=false      -> no-op
 *   - process dead while armed:  heartbeat stale  -> BootStarter
 */
package com.egraf.motionwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

public final class WatchdogReceiver extends BroadcastReceiver {

    private static final String TAG = "WatchdogReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!MotionWakeService.ACTION_WATCHDOG.equals(
                intent != null ? intent.getAction() : null)) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                MotionWakeService.PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(MotionWakeService.PREF_WATCHDOG_ARMED, false)) {
            return;
        }
        if (!prefs.getBoolean(MotionWakeService.PREF_AUTO_START, true)) {
            // User disabled auto-start: don't fight a deliberate stop.
            return;
        }
        long hb = prefs.getLong(MotionWakeService.PREF_HEARTBEAT_TS, 0L);
        long now = SystemClock.elapsedRealtime();
        if (hb != 0 && now - hb < MotionWakeService.WATCHDOG_STALE_MS) {
            return; // service alive and ticking
        }
        Log.i(TAG, "watchdog: service stale (heartbeat age="
                + (hb == 0 ? "never" : (now - hb) + "ms") + ") — reviving");
        try {
            // Same mechanism as BootReceiver: an activity start gives a
            // legitimate foreground context for the FGS start on this
            // Fire build.
            Intent starter = new Intent(context, BootStarter.class);
            starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(starter);
        } catch (Exception e) {
            Log.w(TAG, "watchdog revive failed", e);
        }
    }
}
