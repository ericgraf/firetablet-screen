/*
 * MwA11y — our own AccessibilityService, used purely as a KEYGUARD-DISMISS
 * channel.
 *
 * Why: on this Fire build the keyguard window belongs to uid 1000 and a
 * plain app (uid 10121) is denied both MOTION and generic-KEY injection
 * into it ("Injecting to another application requires INJECT_EVENTS"),
 * and Amazon's internal keyguard binder only accepts whitelisted call
 * processes. The accessibility framework, by contrast, dispatches
 * gestures/keys AS the framework itself (system-uid trusted path), so
 * performGlobalAction(GLOBAL_ACTION_BACK) and dispatchGesture() reach the
 * keyguard with no INJECT_EVENTS checks — the classic keyguard-dismiss
 * mechanism automation apps rely on.
 *
 * It is enabled once from the shell (deploy.sh) via:
 *   settings put secure enabled_accessibility_services
 *       com.egraf.motionwake/.MwA11y
 * which needs WRITE_SECURE_SETTINGS (the shell has it). It receives
 * window-status events only (no content), and stays a no-op sink until
 * ScreenController asks it to dismiss the keyguard.
 *
 * Chain position (ScreenController.dismissKeyguardSwipe): after the
 * AmazonKeyguardService binder probe, before the KeyInjector/swipe
 * fallbacks — both ops here are cheap, in-process, framework-dispatched.
 */
package com.egraf.motionwake;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class MwA11y extends AccessibilityService {

    private static final String TAG = "MwA11y";

    /** Live service handle (bound by the accessibility framework at boot
     *  and after every service (re)start, cleared when destroyed). */
    private static volatile MwA11y sSelf;

    /** Callbacks are delivered on the main looper. Shared: one service. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sSelf = this;
        Log.i(TAG, "connected — a11y keyguard-dismiss channel ready");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Sink: we only need the framework connection, not the events.
    }

    @Override
    public void onInterrupt() {
        // Nothing to reset.
    }

    @Override
    public void onDestroy() {
        if (sSelf == this) {
            sSelf = null;
        }
        super.onDestroy();
    }

    /** True once the framework has bound us and the service is usable. */
    public static boolean available() {
        return sSelf != null;
    }

    /**
     * GLOBAL_ACTION_BACK, dispatched by the accessibility framework (no
     * INJECT_EVENTS check on this path). Cheap first attempt at the dismiss.
     */
    static void globalBack() {
        MwA11y s = sSelf;
        if (s == null) {
            return;
        }
        Log.i(TAG, "performGlobalAction(GLOBAL_ACTION_BACK)");
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    /**
     * Swipe up from bottom-center to top-center — the SAME gesture deploy.sh
     * has proven dismisses this keyguard and sticks. Dispatched from the
     * accessibility framework, so the cross-uid INJECT_EVENTS check that
     * blocks the InputManager route never applies.
     * Returns true if the gesture was queued (the framework may still
     * cancel it — check the keyguard state afterwards).
     */
    static boolean swipeUp(android.content.Context app) {
        MwA11y s = sSelf;
        if (s == null) {
            return false;
        }
        try {
            DisplayMetrics dm = app.getResources().getDisplayMetrics();
            int w = dm.widthPixels > 0 ? dm.widthPixels : 1280;
            int h = dm.heightPixels > 0 ? dm.heightPixels : 800;
            Path path = new Path();
            int x = w / 2;
            path.moveTo(x, h * 0.94f);
            path.lineTo(x, h * 0.12f);
            GestureDescription desc = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, 200L))
                    .build();
            AccessibilityService.GestureResultCallback cb =
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gd) {
                            Log.i(TAG, "a11y swipe completed");
                        }

                        @Override
                        public void onCancelled(GestureDescription gd) {
                            Log.w(TAG, "a11y swipe cancelled (strokes="
                                    + gd.getStrokeCount() + ")");
                        }
                    };
            boolean queued = s.dispatchGesture(desc, cb, MAIN);
            Log.i(TAG, "dispatchGesture(swipe-up) queued=" + queued);
            return queued;
        } catch (Exception e) {
            Log.w(TAG, "a11y swipe failed", e);
            return false;
        }
    }
}
