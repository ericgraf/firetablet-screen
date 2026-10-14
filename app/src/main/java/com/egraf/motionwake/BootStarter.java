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
 *
 * It also (re)launches the DashboardBrowser AS THE HOME ACTIVITY: Fire
 * boots its own recorded home app (FireLauncher) no matter which home
 * is preferred, so we cover it right after our BOOT_COMPLETED start.
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
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
        launchDashboardHome();
        finish();
    }

    private void launchDashboardHome() {
        try {
            SharedPreferences prefs = getSharedPreferences(
                    MotionWakeService.PREFS_NAME, MODE_PRIVATE);
            String url = prefs.getString(MotionWakeService.PREF_BROWSER_URL, "");
            if (url == null || url.length() == 0) {
                Log.i(TAG, "no dashboard URL stored — home skipped");
                return;
            }
            Intent home = new Intent(this, DashboardBrowser.class);
            home.setAction(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            home.putExtra(DashboardBrowser.EXTRA_RELOAD, true);
            startActivity(home);
            Log.i(TAG, "home set to dashboard");
        } catch (Exception e) {
            Log.w(TAG, "home set failed", e);
        }
    }
}
