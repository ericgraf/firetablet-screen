/*
 * KeyguardDismissing — tiny transparent helper: request the system keyguard
 * to be dismissed from a visible (transparent) activity, then finish.
 *
 * Why: on this Fire build the keyguard REAPPEARS after every screen-off
 * timeout (offReason=TIMEOUT). While it is up:
 *   * a user has to swipe it away, and
 *   * its window pins the display's screen-off timeout to 10 s
 *     (its userActivityTimeout attribute) — so the screen goes back to
 *     sleep ~10-40 s after any wake.
 * The keyguard's off-reason is sticky: once dismissed by the USER it stays
 * dismissed across sleep cycles (that's the state of the working device).
 * KeyguardManager.requestDismissKeyguard() from a visible activity IS that
 * user dismissal, so we fire it on every wake path (see openDashboard()).
 * The activity is transparent and finishes immediately.
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

public class KeyguardDismissing extends Activity {

    private static final String TAG = "KeyguardDismiss";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            KeyguardManager km =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                km.requestDismissKeyguard(this, null);
                Log.i(TAG, "requestDismissKeyguard sent");
            }
        } catch (Exception e) {
            Log.w(TAG, "dismiss failed", e);
        } finally {
            finish();
        }
    }
}
