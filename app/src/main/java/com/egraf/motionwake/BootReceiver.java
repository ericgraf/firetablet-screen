/*
 * BootReceiver — restarts the monitor after reboot when the user has
 * enabled "start after boot" in the app.
 */
package com.egraf.motionwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        boolean bootish = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!bootish) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                MotionWakeService.PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(MotionWakeService.PREF_AUTO_START, true)) {
            Log.i(TAG, "auto-start enabled — starting monitor");
            ControlReceiver.startService(context.getApplicationContext());
        } else {
            Log.i(TAG, "auto-start disabled — skipping");
        }
    }
}
