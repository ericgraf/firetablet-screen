/*
 * KeyguardSwipe — a tiny, transparent activity whose only job is to dismiss
 * the system keyguard with a REAL swipe-up gesture, injected through
 * InputManager (same mechanism KeyInjector relies on: allowed without
 * INJECT_EVENTS while our task-behind window makes the focus walk resolve
 * to null, and the gesture lands on the topmost window at those
 * coordinates — the keyguard, when it is up).
 *
 * Why: on this Fire build the keyguard reappears after every screen-off
 * timeout (offReason=TIMEOUT), and requestDismissKeyguard() from a visible
 * activity is rejected (needs Amazon's signature|amazon
 * ENABLE_KEYGUARD_FLAGS permission). But a genuine user swipe dismisses it
 * AND makes the dismissal sticky (offReason=USER — survives subsequent
 * sleep cycles), verified with 'input swipe 640 750 640 100 200' (the
 * gesture deploy.sh applies after every install). While the keyguard is up
 * it also pins the display's screen-off timeout to 10 s, so the screen
 * re-sleeps ~10-40 s after every wake. Hence: every wake path + boot runs
 * this dismiss.
 *
 * Step: check isKeyguardShowing() -> if up, inject
 *   DOWN (w/2, 0.94h) -> 8 interpolated MOVEs -> UP (w/2, 0.12h), ~200ms,
 * then wait for the framework to process and re-check. Repeat until the
 * keyguard is gone or MAX_PASSES runs out.
 *
 * ScreenController starts this task (setLaunchTaskBehind) and polls
 * {@link #isDone} / {@link #resultOk} on its background thread — same
 * contract as KeyInjector.
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

public class KeyguardSwipe extends Activity {

    private static final String TAG = "KeyguardSwipe";
    /** Max inject-and-verify passes. Each pass is ~0.55s, so the total
     *  budget is under 4s. */
    private static final int MAX_PASSES = 6;
    /** Interpolated MOVE steps between DOWN and UP. */
    private static final int SWIPE_STEPS = 8;

    private static final Object LOCK = new Object();
    private static volatile int sSeq = -1;
    private static volatile boolean sOk = false;

    private int mSeq = -1;
    private int mX;
    private int mYFrom;
    private int mYTo;

    public static void record(int seq, boolean ok) {
        synchronized (LOCK) {
            sOk = ok;
            sSeq = seq;
        }
    }

    public static boolean isDone(int seq) {
        synchronized (LOCK) {
            return sSeq == seq;
        }
    }

    public static boolean resultOk(int seq) {
        synchronized (LOCK) {
            return sSeq == seq && sOk;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSeq = getIntent().getIntExtra("seq", -1);
        // Geometry relative to the screen (matches deploy.sh's
        // 'input swipe 640 750 640 100 200' on 1280x800).
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels > 0 ? dm.widthPixels : 1280;
        int h = dm.heightPixels > 0 ? dm.heightPixels : 800;
        mX = w / 2;
        mYFrom = (int) (h * 0.94f);
        mYTo = (int) (h * 0.12f);
        // Plain, visible, touchable window that finishes within seconds
        // (same posture as KeyInjector — it must stay in the focus walk).
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.dimAmount = 0f;
        getWindow().setAttributes(lp);
        // Give the window a moment to attach, then start stepping.
        getWindow().getDecorView().postDelayed(this::step, 350L);
    }

    private int mPass = 0;

    private void step() {
        boolean showing = keyguardShowing(this);
        Log.i(TAG, "pass=" + mPass + " gate showing=" + showing
                + " focus=" + focusDump());
        if (!showing) {
            Log.i(TAG, "keyguard not showing — nothing to dismiss");
            record(mSeq, true);
            finish();
            return;
        }
        int down = -999, up = -999;
        String err = "";
        try {
            android.hardware.input.InputManager im =
                    (android.hardware.input.InputManager)
                            getSystemService(Context.INPUT_SERVICE);
            java.lang.reflect.Method m =
                    android.hardware.input.InputManager.class.getMethod(
                            "injectInputEvent", InputEvent.class, int.class);
            long downTime = SystemClock.uptimeMillis();
            down = injectOne(m, im, downTime, downTime,
                    MotionEvent.ACTION_DOWN, mX, mYFrom);
            for (int i = 1; i <= SWIPE_STEPS; i++) {
                Thread.sleep(15L);
                long t = SystemClock.uptimeMillis();
                int y = mYFrom + (mYTo - mYFrom) * i / SWIPE_STEPS;
                injectOne(m, im, downTime, t,
                        MotionEvent.ACTION_MOVE, mX, y);
            }
            Thread.sleep(15L);
            up = injectOne(m, im, downTime,
                    SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, mX, mYTo);
        } catch (Throwable e) {
            err = String.valueOf(e);
            if (!sDumped) {
                sDumped = true;
                java.io.StringWriter sw = new java.io.StringWriter();
                Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) e).getCause() != null)
                        ? ((java.lang.reflect.InvocationTargetException) e).getCause() : e;
                cause.printStackTrace(new java.io.PrintWriter(sw));
                Log.w(TAG, "inject failure detail:" + sw);
            }
        }
        Log.i(TAG, "pass=" + mPass + " injected down=" + down + " up=" + up
                + (err.isEmpty() ? "" : " err=" + err));
        // The keyguard dismissal is processed by the framework
        // asynchronously — give it a beat, then check the real target.
        try {
            Thread.sleep(350L);
        } catch (InterruptedException ignored) {
        }
        boolean still = keyguardShowing(this);
        Log.i(TAG, "pass=" + mPass + " after showing=" + still);
        if (!still) {
            Log.i(TAG, "swipe dismissed keyguard, pass=" + mPass);
            record(mSeq, true);
            finish();
            return;
        }
        mPass++;
        if (mPass >= MAX_PASSES) {
            Log.w(TAG, "keyguard still showing after " + mPass + " swipes");
            record(mSeq, false);
            finish();
            return;
        }
        getWindow().getDecorView().postDelayed(this::step, 300L);
    }

    /** Diagnostic: which window the framework considers focused right now
     *  (compares the injection context against the working adb-shell case). */
    private static String focusDump() {
        try {
            java.lang.Process p = java.lang.Runtime.getRuntime().exec(new String[]{
                    "sh", "-c",
                    "dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp' | tr -d ' '"
            });
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            p.waitFor();
            return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.US_ASCII) : "(none)";
        } catch (Exception e) {
            return "(err)";
        }
    }

    private static boolean sDumped = false;

    /** Inject one motion event; returns 0 accepted / 1 rejected / -999 odd. */
    private static int injectOne(java.lang.reflect.Method m, Object im,
                                 long downTime, long eventTime, int action,
                                 float x, float y) throws Exception {
        MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            Object r = m.invoke(im, ev,
                    1 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT */);
            if (r instanceof Boolean) {
                return (Boolean) r ? 0 : 1;
            }
            if (r instanceof Number) {
                return ((Number) r).intValue();
            }
            return -999;
        } finally {
            ev.recycle();
        }
    }

    /**
     * isKeyguardShowing() exists at runtime on this Fire build but is missing
     * from the vendored compile jar, so reflect it; fall back to
     * isKeyguardLocked(); when neither is available assume "showing" and
     * let the loop decide (a stray swipe on the dashboard is benign).
     */
    static boolean keyguardShowing(Context app) {
        try {
            KeyguardManager km = (KeyguardManager)
                    app.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) {
                return true;
            }
            java.lang.reflect.Method m =
                    KeyguardManager.class.getMethod("isKeyguardShowing");
            return Boolean.TRUE.equals(m.invoke(km));
        } catch (Exception e) {
            try {
                KeyguardManager km = (KeyguardManager)
                        app.getSystemService(Context.KEYGUARD_SERVICE);
                return km != null && km.isKeyguardLocked();
            } catch (Exception e2) {
                return true;
            }
        }
    }

    /**
     * Preferred dismiss: this build exposes an Amazon-internal keyguard
     * binder service that dismisses the keyguard directly (no input
     * injection, so no INJECT_EVENTS/uid-window checks) and makes the
     * dismissal STICKY (offReason=USER — survives subsequent sleep cycles,
     * and the 10 s screen-off-timeout pin is released).
     *
     * Found empirically by sweeping 'service call AmazonKeyguardService N'
     * on the device (2026-10-14): transaction 5 is the void dismiss op —
     * keyguard 'showing' flipped true→false from shell uid, and the state
     * proved sticky across /sleep+timeout cycles. The other probed codes
     * were queries, access-denied, or no-ops.
     *
     * 'service call' execs as OUR uid (10121); if the binder still rejects
     * us the output carries the exception text and we fall through to the
     * next strategy. Same contract as swipeViaShell: returns true only if
     * the keyguard is actually gone afterwards.
     */
    static boolean dismissViaAmazonService(Context app) {
        try {
            java.lang.Process p = java.lang.Runtime.getRuntime().exec(new String[]{
                    "service", "call", "AmazonKeyguardService", "5"
            });
            String text = new String(readAll(p.getInputStream()),
                            java.nio.charset.StandardCharsets.UTF_8)
                    + "|" + new String(readAll(p.getErrorStream()),
                            java.nio.charset.StandardCharsets.UTF_8);
            int rc = p.waitFor();
            Log.i(TAG, "amazon keyguard dismiss rc=" + rc + " out/err=[" + text.trim() + "]");
            try {
                Thread.sleep(700L);
            } catch (InterruptedException ignored) {
            }
            boolean showing = keyguardShowing(app);
            Log.i(TAG, "amazon keyguard dismiss done, showing=" + showing);
            return !showing;
        } catch (Exception e) {
            Log.w(TAG, "amazon keyguard dismiss failed", e);
            return false;
        }
    }

    /** Drain a stream fully (bounded to 64 KB of log output). */
    static byte[] readAll(java.io.InputStream in) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total >= 65536) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return bos.toByteArray();
    }

    /**
     * Last-resort dismiss: run the 'input swipe' binary as OUR uid (the same
     * gesture deploy.sh applies from adb shell). InputManager sees the
     * process uid, so this probes whether the dispatcher accepts our uid
     * for the keyguard's pointer window even without INJECT_EVENTS.
     * Returns true only if the keyguard is actually gone afterwards.
     */
    static boolean swipeViaShell(Context app) {
        try {
            java.lang.Process p = java.lang.Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "input swipe 640 750 640 100 200"
            });
            java.io.InputStream out = p.getInputStream();
            java.io.InputStream err = p.getErrorStream();
            byte[] bo = new byte[2048];
            int no = out.read(bo);
            p.waitFor();
            byte[] be = new byte[2048];
            int ne = err.read(be);
            String text = new String(bo, 0, Math.max(0, no), java.nio.charset.StandardCharsets.UTF_8)
                    + "|" + new String(be, 0, Math.max(0, ne), java.nio.charset.StandardCharsets.UTF_8);
            Log.i(TAG, "shell swipe rc=" + p.exitValue() + " out/err=[" + text.trim() + "]");
            try {
                Thread.sleep(700L);
            } catch (InterruptedException ignored) {
            }
            boolean showing = keyguardShowing(app);
            Log.i(TAG, "shell swipe done, showing=" + showing);
            return !showing;
        } catch (Exception e) {
            Log.w(TAG, "shell swipe failed", e);
            return false;
        }
    }
}
