/*
 * ControlReceiver — single entry point for external control intents:
 *   com.egraf.motionwake.action.START_SERVICE
 *   com.egraf.motionwake.action.STOP_SERVICE
 *   com.egraf.motionwake.action.WAKE
 *   com.egraf.motionwake.action.SLEEP
 *
 * WAKE/SLEEP act instantly (even while the service is stopped) and also
 * reset the idle timer for the running service.
 */
package com.egraf.motionwake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class ControlReceiver extends BroadcastReceiver {

    private static final String TAG = "ControlReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        Log.i(TAG, "received " + action);
        if (MotionWakeService.ACTION_WAKE.equals(action)) {
            MotionWakeService.touchMotion();
            new ScreenController(app).wake();
        } else if (MotionWakeService.ACTION_SLEEP.equals(action)) {
            new ScreenController(app).sleep();
        } else if (MotionWakeService.ACTION_START.equals(action)) {
            startService(app);
        } else if (MotionWakeService.ACTION_STOP.equals(action)) {
            try {
                app.stopService(new Intent(app, MotionWakeService.class));
            } catch (Exception e) {
                Log.w(TAG, "stopService failed", e);
            }
        }
    }

    static void startService(Context app) {
        Intent intent = new Intent(app, MotionWakeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }
}
