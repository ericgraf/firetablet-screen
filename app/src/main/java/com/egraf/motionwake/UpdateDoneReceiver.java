/*
 * UpdateDoneReceiver — records OTA state transitions for /update/status.
 *
 * Two signals:
 *  - SESSION_COMMITTED ("android.content.pm.action.SESSION_COMMITTED"):
 *    PackageInstaller fires the PendingIntent we handed it from
 *    UpdateInstaller once the commit step has run — meaning the install
 *    is underway/accepted for our session.
 *  - INITIALIZATION_COMPLETE ("android.content.pm.action.INITIALIZATION_
 *    COMPLETE") filtered to our package: fires when our package (re)install
 *    fully completed. Belt-and-braces; delivered to the NEW process
 *    instance (the old one died during the replace).
 *
 * Manifest-declared (exported=false): survives the app's own process
 * death during the self-replace. MotionWakeService reads the timestamps
 * via SharedPreferences — no cross-boundary state needed.
 */
package com.egraf.motionwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class UpdateDoneReceiver extends BroadcastReceiver {

    private static final String TAG = "UpdateDoneReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Fires on EVERY replace of our package, including the
            // host-driven installs (`adb install` over LAN/USB) that finish
            // a job after one of our in-app channels failed on this build.
            // Stamp "applied" only when an in-app /update attempt is still
            // pending — a plain deploy bootstrap (no attempt) must not
            // show up as an OTA.
            android.content.SharedPreferences p = context.getSharedPreferences(
                    MotionWakeService.PREFS_NAME, Context.MODE_PRIVATE);
            long attempt = p.getLong(MotionWakeService.PREF_OTA_ATTEMPT_TS, 0L);
            long applied = p.getLong(MotionWakeService.PREF_OTA_APPLIED_TS, 0L);
            if (attempt > 0L && applied == 0L) {
                long now = System.currentTimeMillis();
                MotionWakeService.markOtaApplied(context, now);
                Log.i(TAG, "MY_PACKAGE_REPLACED closed a pending in-app attempt — OTA applied, ts=" + now);
            } else {
                Log.i(TAG, "MY_PACKAGE_REPLACED (no pending in-app attempt — plain deploy, not stamped)");
            }
            return;
        }
        long now = System.currentTimeMillis();
        MotionWakeService.markOtaApplied(context, now);
        Log.i(TAG, "received " + action + " — OTA applied, ts=" + now);
    }
}
