/*
 * BootStarter — transparent springboard activity for boot auto-start.
 *
 * Why an activity: on this Fire build, a BOOT_COMPLETED (implicit)
 * background broadcast to a stopped app is allowed to run the receiver,
 * but NOT to start a foreground service ("Background execution not
 * allowed"). Launching a (translucent, immediately finished) activity
 * gives us a legitimate foreground context from which the foreground
 * service start is permitted — the same mechanism the KeyInjector
 * wake/sleep path already relies on.
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public final class BootStarter extends Activity {

    private static final String TAG = "BootStarter";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "boot starter — starting service");
        try {
            ControlReceiver.startService(getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "service start failed", e);
        }
        finish();
    }
}
