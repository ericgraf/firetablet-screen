/*
 * KeyInjector — a tiny, transparent, focusable activity whose only job is to
 * inject one key event (KEYCODE_SLEEP / KEYCODE_WAKEUP) through InputManager
 * and finish.
 *
 * Why: InputManagerService allows an app to inject input events WITHOUT the
 * signature INJECT_EVENTS permission when *our window is the foreground one*.
 * The service context is not "foreground", so it hops through this activity:
 * resume -> onWindowFocusChanged(true) -> inject -> record result -> finish.
 *
 * The service calls startInjector(seq, keycode) and polls {@link #isDone} /
 * {@link #resultOk} on its background thread.
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.WindowManager;

import java.lang.reflect.Method;

public class KeyInjector extends Activity {

    private static final Object LOCK = new Object();
    private static volatile int sSeq = -1;
    private static volatile boolean sOk = false;

    private int mSeq = -1;
    private int mKeyCode = -1;
    private boolean mDone = false;

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
        mKeyCode = getIntent().getIntExtra("keycode", -1);
        // Keep the window plain, visible and *touchable*. FLAG_NOT_TOUCHABLE
        // windows are skipped by the focus walk, and this window must stay in
        // it (see below). It is transparent and finishes within seconds, so
        // any momentary input side effects are harmless.
        //
        // WHY INJECTION WORKS ON THIS FIRE BUILD (decoded from services.jar):
        //   * InputDispatcher only accepts our injectInputEvent when there is
        //     NO focused window (mCurrentFocus == null) or when the focused
        //     window belongs to our uid (no INJECT_EVENTS needed either way).
        //   * DisplayContent.findFocusedWindow walks ALL windows BOTTOM->TOP
        //     and stops at the FIRST one that canReceiveKeys(); with an
        //     mFocusedApp set, a hit window whose task sits BEHIND the
        //     focused app's task resolves the walk to a NULL focus window.
        //   * ScreenController therefore starts this task via
        //     ActivityOptions.setLaunchTaskBehind(true): our window becomes
        //     the first keyable window in the walk (above the wallpaper,
        //     below Fully's), and the walk resolves to NULL focus while we
        //     are alive -> injection is allowed.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.dimAmount = 0f;
        getWindow().setAttributes(lp);
        if (mKeyCode < 0) {
            record(mSeq, false);
            finish();
            return;
        }
        // If focus never comes (shouldn't happen), inject after a grace period.
        getWindow().getDecorView().postDelayed(this::doInject, 400L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            doInject();
        }
    }

    private void doInject() {
        if (mDone || mKeyCode < 0) {
            return;
        }
        mDone = true;
        // The injection is only accepted once OUR window is the top one; when
        // this activity is background-started that happens slightly after
        // focus is first granted — so retry until it sticks (or we time out).
        long deadline = SystemClock.uptimeMillis() + 3000L;
        boolean ok = false;
        int lastDown = -999, lastUp = -999;
        String err = "";
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                android.hardware.input.InputManager im =
                        (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
                // injectInputEvent is @hide in the SDK stubs — reflect it.
                Method m = android.hardware.input.InputManager.class.getMethod(
                        "injectInputEvent", InputEvent.class, int.class);
                long t1 = SystemClock.uptimeMillis();
                lastDown = ((Number) m.invoke(im,
                        new KeyEvent(t1, t1, KeyEvent.ACTION_DOWN, mKeyCode, 0, 0, 0, 0),
                        1 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT */)).intValue();
                Thread.sleep(80L);
                long t2 = SystemClock.uptimeMillis();
                lastUp = ((Number) m.invoke(im,
                        new KeyEvent(t2, t2, KeyEvent.ACTION_UP, mKeyCode, 0, 0, 0, 0),
                        1 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT */)).intValue();
                // 0 = SUCCESS, 1 = PERMISSION_DENIED (not top app yet),
                // 3 = TIMEOUT, other negative = FAILED.
                ok = lastDown == 0 /* INJECT_INPUT_EVENT_RESULT_SUCCESS */
                        && lastUp == 0;
                if (ok) {
                    break;
                }
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            SystemClock.sleep(200L);
        }
        android.util.Log.i("KeyInjector", "injected code=" + mKeyCode
                + " down=" + lastDown + " up=" + lastUp
                + (err.isEmpty() ? "" : " err=" + err) + " ok=" + ok);
        record(mSeq, ok);
        finish();
    }
}
