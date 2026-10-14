/*
 * KeyInjector — a tiny, transparent, focusable activity whose only job is to
 * inject one key event (KEYCODE_SLEEP / KEYCODE_WAKEUP) through InputManager
 * and stay alive until the screen state actually flips.
 *
 * Why: InputManagerService allows an app to inject input events WITHOUT the
 * signature INJECT_EVENTS permission when *our window is the foreground one*.
 * The service context is not "foreground", so it hops through this activity:
 * resume -> onWindowFocusChanged(true) -> inject -> poll screen state -> finish.
 *
 * The screen flip happens in the framework ASYNCHRONOUSLY after the event is
 * accepted into the dispatcher — if this activity finishes right after
 * injectInputEvent returns, the window dies before the framework processes the
 * key and the event is dropped (observed on this Fire build). So: inject, then
 * poll PowerManager.isInteractive() (keeping the window alive) until the
 * target state is reached or the budget runs out.
 *
 * The service calls startInjector(seq, keycode) and polls {@link #isDone} /
 * {@link #resultOk} on its background thread.
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public class KeyInjector extends Activity {

    private static final String TAG = "KeyInjector";

    /** Poll cadence while waiting for the screen state to flip. */
    private static final long STEP_MS = 300L;
    /** Total budget for the state to flip after we come up. */
    private static final int MAX_PASSES = 12; // ~3.6s

    private static final Object LOCK = new Object();
    private static volatile int sSeq = -1;
    private static volatile boolean sOk = false;

    private int mSeq = -1;
    private int mKeyCode = -1;

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
        //     below the home), and the walk resolves to NULL focus while we
        //     are alive -> injection is allowed.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.dimAmount = 0f;
        getWindow().setAttributes(lp);
        if (mKeyCode < 0) {
            record(mSeq, false);
            finish();
            return;
        }
        // Give the window a moment to attach, then start polling.
        getWindow().getDecorView().postDelayed(this::step, 300L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Nothing to do: step() runs regardless (window attached + focus).
    }

    private int mPass = 0;

    private void step() {
        if (mKeyCode < 0) {
            record(mSeq, false);
            finish();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean screenOn = pm != null && pm.isInteractive();
        boolean wantSleep = (mKeyCode == KeyEvent.KEYCODE_SLEEP);
        boolean target = wantSleep ? !screenOn : screenOn;
        if (target) {
            Log.i(TAG, "code=" + mKeyCode + " already at target, pass=" + mPass);
            record(mSeq, true);
            finish();
            return;
        }
        // Inject the key pair (the dispatcher may still be settling on the
        // early passes — re-injecting an already-flipped, in-target key is a
        // harmless no-op, so retrying is safe).
        int down = -999, up = -999;
        String err = "";
        try {
            android.hardware.input.InputManager im =
                    (android.hardware.input.InputManager)
                            getSystemService(Context.INPUT_SERVICE);
            java.lang.reflect.Method m =
                    android.hardware.input.InputManager.class.getMethod(
                            "injectInputEvent", InputEvent.class, int.class);
            long t1 = SystemClock.uptimeMillis();
            Object dR = m.invoke(im,
                    new KeyEvent(t1, t1, KeyEvent.ACTION_DOWN, mKeyCode, 0, 0, 0, 0),
                    1 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT */);
            Thread.sleep(60L); // let the DOWN settle before UP
            long t2 = SystemClock.uptimeMillis();
            Object uR = m.invoke(im,
                    new KeyEvent(t2, t2, KeyEvent.ACTION_UP, mKeyCode, 0, 0, 0, 0),
                    1 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT */);
            down = describe(dR);
            up = describe(uR);
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        // Give the framework a beat, then check the real target: screen state.
        try {
            Thread.sleep(120L);
        } catch (InterruptedException ignored) {
        }
        mPass++;
        screenOn = pm != null && pm.isInteractive();
        if ((wantSleep && !screenOn) || (!wantSleep && screenOn)) {
            Log.i(TAG, "code=" + mKeyCode + " injected down=" + down
                    + " up=" + up + (err.isEmpty() ? "" : " err=" + err)
                    + " ok=true pass=" + mPass);
            record(mSeq, true);
            finish();
            return;
        }
        if (mPass >= MAX_PASSES) {
            Log.i(TAG, "code=" + mKeyCode + " injected down=" + down
                    + " up=" + up + (err.isEmpty() ? "" : " err=" + err)
                    + " ok=false pass=" + mPass);
            record(mSeq, false);
            finish();
            return;
        }
        getWindow().getDecorView().postDelayed(this::step, STEP_MS);
    }

    /** 0 = accepted, 1 = rejected, -999 = unexpected shape (log aid). */
    private static int describe(Object result) {
        if (result instanceof Boolean) {
            return (Boolean) result ? 0 : 1;
        }
        if (result instanceof Number) {
            return ((Number) result).intValue();
        }
        return -999;
    }
}
