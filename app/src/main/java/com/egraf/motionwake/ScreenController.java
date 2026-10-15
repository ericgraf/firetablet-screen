/*
 * ScreenController — puts the display to sleep / wakes it from a service or
 * receiver. Multiple strategies because the API surface differs per build:
 *
 * Sleep (tried in order):
 *   1. IPowerManager.goToSleep(long,int,int) via reflection — works when the
 *      DEVICE_POWER permission is granted (privileged/system installs).
 *   2. KEYCODE_SLEEP injected from the service — allowed (without
 *      INJECT_EVENTS) because our own dashboard window is the focused one;
 *      the framework's unhandled-KEY_SLEEP path performs the sleep
 *      (verified on this Fire build with 'input keyevent 223').
 *   3. KEYCODE_SLEEP via the KeyInjector activity (needs INJECT_EVENTS;
 *      verified on this Fire build to beat the Fully launcher's deprecated
 *      FULL_WAKE_LOCK).
 *   4. "Short screen_off_timeout" dance: WRITE_SETTINGS lets us lower the
 *      system screen-off timeout to a few seconds and let PowerManagerService
 *      perform its own internal timeout sleep (works when nobody holds a
 *      deprecated full wake lock or a window holds FLAG_KEEP_SCREEN_ON).
 *   5. If the screen still stays on, a deprecated FULL_WAKE_LOCK is holding it
 *      (on this tablet: the Fully launcher's "always on home"). Identify the
 *      holder from `dumpsys power`, force-stop it, and retry step 4 (needs
 *      the DUMP permission to read the holder).
 *   6. Raw KEY_SLEEP input-event injection (last resort; needs /dev/input).
 *
 * Wake (tried in order):
 *   1. FULL_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP (only needs WAKE_LOCK).
 *   2. IPowerManager.wakeUp(...) via reflection (needs DEVICE_POWER).
 *   3. KEYCODE_WAKEUP via InputManager (needs INJECT_EVENTS).
 *   4. Raw KEY_WAKEUP input-event injection.
 *
 * All work runs on a single background executor — safe from BroadcastReceiver
 * (main-thread) timeouts.
 */
package com.egraf.motionwake;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ScreenController {

    private static final String TAG = "ScreenControl";
    private static final String WAKE_LOCK_TAG = "motionwatch:wake";
    private static final long WAKE_LOCK_MILLIS = 5000L;
    /** Total budget for the timeout sleep: Fire runs the ~60s dim ClockDream
     *  screensaver before the display truly sleeps, and the nap itself can
     *  take a little while to kick in, so 150s covers the full pipeline. */
    private static final long SLEEP_WAIT_DEADLINE_MS = 150000L;
    /** Temp screen_off_timeout used while waiting for the timeout sleep. */
    private static final int SLEEP_TIMEOUT_MS = 5000;

    // Linux input-event protocol (struct input_event), little-endian on ARM.
    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int SCAN_CODE_WAKEUP = 112; // KEY_WAKEUP
    private static final int SCAN_CODE_SLEEP = 122;  // KEY_SLEEP

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private final Context mApp;
    private final PowerManager mPower;

    ScreenController(Context context) {
        mApp = context.getApplicationContext();
        mPower = (PowerManager) mApp.getSystemService(Context.POWER_SERVICE);
        ensureWakefulnessTracking(mApp);
    }

    boolean isScreenOn() {
        return mPower == null || mPower.isInteractive();
    }

    // -------------------------------------------------- wakefulness state

    /**
     * Live PowerManager wakefulness (AWAKE / DREAMING / ASLEEP).
     *
     * On this Fire build a screen timeout first starts a dim clocksaver
     * (ClockDream screensaver) which lasts ~60s before the device truly
     * sleeps; while it runs, isInteractive() is still true. So "is the
     * screen on?" decisions here use wakefulness instead: DREAMING or
     * ASLEEP both count as "screen not on", which is exactly what the wall
     * tablet wants (dim ambient clock is fine, power drops, wake works).
     */
    private static volatile int sWakefulness = -1;
    private static final Object TRACK_LOCK = new Object();
    private static boolean sTracking = false;
    // PowerManager.Wakefulness values (stable since API 23; the project's
    // vendored android.jar predates the names, so keep the raw ints).
    private static final int WF_ASLEEP = 0;
    private static final int WF_DREAMING = 1;
    private static final int WF_AWAKE = 2;

    /** Idempotent; safe to call from any constructor of this service app. */
    static void ensureWakefulnessTracking(Context app) {
        synchronized (TRACK_LOCK) {
            if (sTracking) {
                return;
            }
            sTracking = true;
            try {
                // Initial guess from the current state; the broadcasts below
                // then keep it live.
                PowerManager pm0 = (PowerManager) app
                        .getSystemService(Context.POWER_SERVICE);
                sWakefulness = (pm0 != null && pm0.isInteractive())
                        ? WF_AWAKE : WF_ASLEEP;
                // This Fire build lacks PowerManager.addWakefulnessChangeCallback
                // (absent from its framework.jar), but it does broadcast the
                // dream lifecycle — which is exactly the "screensaver before
                // sleep" phase we need to see — plus the classic screen
                // power broadcasts. That's enough to derive AWAKE/DREAMING/
                // ASLEEP.
                android.content.IntentFilter f = new android.content.IntentFilter();
                f.addAction(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_SCREEN_OFF);
                f.addAction("android.intent.action.DREAMING_STARTED");
                f.addAction("android.intent.action.DREAMING_STOPPED");
                app.registerReceiver(new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        String a = i == null ? null : i.getAction();
                        if (a == null) {
                            return;
                        }
                        if ("android.intent.action.DREAMING_STARTED".equals(a)) {
                            sWakefulness = WF_DREAMING;
                        } else if ("android.intent.action.DREAMING_STOPPED".equals(a)) {
                            PowerManager pm = (PowerManager) c
                                    .getSystemService(Context.POWER_SERVICE);
                            sWakefulness = (pm != null && pm.isInteractive())
                                    ? WF_AWAKE : WF_ASLEEP;
                        } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                            sWakefulness = WF_AWAKE;
                        } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                            sWakefulness = WF_ASLEEP;
                        }
                    }
                }, f);
            } catch (Throwable t) {
                Log.d(TAG, "screen state receivers unavailable", t);
            }
        }
    }

    static boolean isAwake() {
        return sWakefulness == WF_AWAKE;
    }

    static String screenStateName() {
        switch (sWakefulness) {
            case WF_AWAKE:
                return "awake";
            case WF_DREAMING:
                return "dreaming";
            case WF_ASLEEP:
                return "asleep";
            default:
                return "unknown";
        }
    }

    /** Unique sequence for injector-activity results — the static record in
     *  the activities persists across instances, so the seq must be global
     *  to avoid reading a stale completed run. */
    private static final java.util.concurrent.atomic.AtomicInteger INJECT_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Inject a key event on a transparent KeyInjector activity (InputManager
     * allows injection without INJECT_EVENTS while our window is foreground).
     * Returns true if the event pair was accepted.
     */
    private boolean injectKeycode(int keycode) {
        return injectKeycode(keycode, false);
    }

    private boolean injectKeycode(int keycode, boolean injectAlways) {
        final int seq = INJECT_SEQ.getAndIncrement();
        try {
            android.content.Intent i = new android.content.Intent(mApp, KeyInjector.class);
            i.putExtra("seq", seq);
            i.putExtra("keycode", keycode);
            i.putExtra("inject_always", injectAlways);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            // Start the injector task BEHIND the foreground task (see the
            // note in KeyInjector.onCreate): Fire's focus walk then resolves
            // to a NULL focus window while the injector is alive, and
            // InputDispatcher accepts our events without INJECT_EVENTS.
            mApp.startActivity(i, taskBehindOptions().toBundle());
            // The injector stays alive until the screen state flips (<=~4s).
            long deadline = SystemClock.uptimeMillis() + 8000L;
            while (SystemClock.uptimeMillis() < deadline) {
                if (KeyInjector.isDone(seq)) {
                    boolean ok = KeyInjector.resultOk(seq);
                    Log.i(TAG, "inject keycode " + keycode + " via KeyInjector ok=" + ok);
                    return ok;
                }
                Thread.sleep(50L);
            }
            Log.w(TAG, "inject keycode " + keycode + " timed out waiting for KeyInjector");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "inject keycode " + keycode + " failed", e);
            return false;
        }
    }

    /** ActivityOptions with setLaunchTaskBehind(true) — @hide in the SDK
     *  stubs, so reflect it. See the note in KeyInjector.onCreate for why
     *  task-behind launch makes unprivileged input injection work. */
    private static android.app.ActivityOptions taskBehindOptions() {
        android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
        try {
            java.lang.reflect.Method m =
                    android.app.ActivityOptions.class.getMethod(
                            "setLaunchTaskBehind", boolean.class);
            opts = (android.app.ActivityOptions) m.invoke(opts, true);
        } catch (Throwable ignored) {
        }
        return opts;
    }

    /**
     * Dismiss the system keyguard and make it STICK. No-op (returns true)
     * when the keyguard is not up.
     *
     * Called on EVERY wake path (motion, API/HA, notification) and at boot:
     * on this build only a user-style dismissal makes it sticky
     * (offReason=USER — it then survives subsequent sleep cycles), and the
     * keyguard while up pins the display's screen-off timeout to 10 s.
     *
     * Strategies (this build, in order):
     *   1. AmazonKeyguardService transaction 5 via 'service call' —
     *      Amazon's internal keyguard binder (dismiss works from shell uid;
     *      from our app uid the service currently rejects the pid — kept as
     *      a cheap probe in case the allowlist widens).
     *   2. Our AccessibilityService (MwA11y): GLOBAL_ACTION_BACK, then the
     *      proven swipe-up gesture — dispatched by the a11y framework
     *      itself (trusted path, no INJECT_EVENTS/uid checks). The channel
     *      of choice when the app's own uid is not dispatch-trusted.
     *   3. KEYCODE_BACK through the KeyInjector null-focus KEY path.
     *   4. MOTION swipe-up (KeyguardSwipe) — needs the dispatcher to accept
     *      our uid for the topmost window (INJECT_EVENTS territory).
     *   5. 'input swipe' binary as our uid (same probe, different code path).
     */
    boolean dismissKeyguardSwipe() {
        if (!KeyguardSwipe.keyguardShowing(mApp)) {
            Log.i(TAG, "keyguard dismiss: not showing — no-op");
            return true;
        }
        if (KeyguardSwipe.dismissViaAmazonService(mApp)) {
            Log.i(TAG, "keyguard dismissed via AmazonKeyguardService");
            return true;
        }
        if (MwA11y.available()) {
            MwA11y.globalBack();
            if (waitForKeyguardGone(1500L)) {
                Log.i(TAG, "keyguard dismissed via a11y GLOBAL_ACTION_BACK");
                return true;
            }
            if (MwA11y.swipeUp(mApp) && waitForKeyguardGone(2500L)) {
                Log.i(TAG, "keyguard dismissed via a11y swipe gesture");
                return true;
            }
            Log.i(TAG, "a11y back+swipe did not dismiss — next strategy");
        }
        if (dismissKeyguardByKey()) {
            return true;
        }
        try {
            int seq = INJECT_SEQ.getAndIncrement();
            Intent i = new Intent(mApp, KeyguardSwipe.class);
            i.putExtra("seq", seq);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            mApp.startActivity(i, taskBehindOptions().toBundle());
            // KeyguardSwipe finishes as soon as the keyguard is gone or the
            // budget runs out (<=~4s normally).
            long deadline = SystemClock.uptimeMillis() + 8000L;
            while (SystemClock.uptimeMillis() < deadline) {
                if (KeyguardSwipe.isDone(seq)) {
                    boolean ok = KeyguardSwipe.resultOk(seq);
                    Log.i(TAG, "keyguard swipe dismiss ok=" + ok);
                    if (ok) {
                        return true;
                    }
                    break;
                }
                Thread.sleep(50L);
            }
        } catch (Exception e) {
            Log.w(TAG, "keyguard swipe dismiss failed", e);
        }
        if (KeyguardSwipe.swipeViaShell(mApp)) {
            return true;
        }
        Log.w(TAG, "keyguard dismiss: all strategies failed, still showing="
                + KeyguardSwipe.keyguardShowing(mApp));
        return false;
    }

    /** Poll until the keyguard no longer shows (or the budget runs out). */
    private boolean waitForKeyguardGone(long ms) {
        long deadline = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!KeyguardSwipe.keyguardShowing(mApp)) {
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                break;
            }
        }
        return !KeyguardSwipe.keyguardShowing(mApp);
    }

    /**
     * Strategy 1: injected BACK key through the KeyInjector key path.
     * NOTE: use the LITERAL 111 — the vendored android.jar in this project
     * defines KEYCODE_BACK=4 (verified on-device: 'input keyevent 111'
     * dismisses the lock screen; the jar constant would inject UNKNOWN).
     */
    private static final int KEY_BACK_REAL = 111;

    private boolean dismissKeyguardByKey() {
        try {
            boolean injected = injectKeycode(KEY_BACK_REAL, true);
            Log.i(TAG, "keyguard KEY_BACK injected ok=" + injected);
            long deadline = SystemClock.uptimeMillis() + 4000L;
            while (SystemClock.uptimeMillis() < deadline) {
                if (!KeyguardSwipe.keyguardShowing(mApp)) {
                    Log.i(TAG, "keyguard dismissed via KEY_BACK");
                    return true;
                }
                Thread.sleep(100L);
            }
            Log.w(TAG, "keyguard still showing after KEY_BACK");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "keyguard KEY_BACK dismiss failed", e);
            return false;
        }
    }

    void wake() {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                runWake();
            }
        });
    }

    /** Wake, returning a Future so callers (e.g. the HTTP API) can await completion. */
    java.util.concurrent.Future<?> wakeFuture() {
        return EXEC.submit(new Runnable() {
            @Override
            public void run() {
                runWake();
            }
        });
    }

    private void runWake() {
        boolean on = isAwake();
        if (!on) {
            acquireWakeLock();
            SystemClock.sleep(150L);
            on = isAwake();
        }
        if (!on) {
            binderWake();
            SystemClock.sleep(300L);
            on = isAwake();
        }
        if (!on) {
            // Direct injection with the dashboard (our uid) as the focused
            // window targets the dashboard, which lets the framework's
            // unhandled-KEY_WAKEUP path do the wake.
            injectKeyFromService(KeyEvent.KEYCODE_WAKEUP);
            SystemClock.sleep(500L);
            on = isAwake();
        }
        if (!on) {
            if (injectKeycode(KeyEvent.KEYCODE_WAKEUP)) {
                SystemClock.sleep(500L);
            }
            on = isAwake();
        }
        if (!on) {
            injectKey(SCAN_CODE_WAKEUP);
        }
        Log.i(TAG, "screen wake requested (awake=" + on + ")");
    }

    void sleep() {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                runSleep();
            }
        });
    }

    /** Sleep, returning a Future so callers (e.g. the HTTP API) can await completion. */
    java.util.concurrent.Future<?> sleepFuture() {
        return EXEC.submit(new Runnable() {
            @Override
            public void run() {
                runSleep();
            }
        });
    }

    private void runSleep() {
        boolean ok = !isAwake();
        if (!ok) {
            // 1) framework API (instant; needs DEVICE_POWER, which this build
            //    does not grant to us — keep first anyway, it's a no-op then).
            ok = tryGoToSleep();
        }
        if (!ok) {
            // 2) Our own wake lock (FULL_WAKE_LOCK) holds the screen on for
            //    ~5s after a wake — drop it before the timeout sleep below.
            releaseOwnWakeLock();
            // 3) A deprecated FULL_WAKE_LOCK held by some other app would
            //    block the timeout sleep (typical offender: a kiosk
            //    launcher's "always on home") — clear it before the long
            //    timeout wait below.
            String victim = findFullWakeLockHolder();
            if (victim != null) {
                Log.i(TAG, "sleep: force-stopping FULL_WAKE_LOCK holder " + victim);
                forceStopPackage(victim);
                SystemClock.sleep(1000L); // let the holder release its locks
            }
            // 4) WORKHORSE on this build: PowerManagerService's own screen-off
            //    timeout (WRITE_SETTINGS). A short timeout lets PMS perform
            //    the sleep internally — no injector uid involved, so it beats
            //    the DEVICE_POWER/INJECT_EVENTS gates (this is how we beat
            //    the Fully launcher's "always on home" back in the day).
            //    Note: on this Fire build the nap first shows the ~60s dim
            //    ClockDream screensaver; DREAMING already counts as success.
            ok = timeoutTrickOnce();
        }
        if (!ok) {
            // 5) KEYCODE_SLEEP key paths (work when our uid is privileged).
            injectKeyFromService(KeyEvent.KEYCODE_SLEEP);
            SystemClock.sleep(800L);
            ok = !isAwake();
            if (!ok && injectKeycode(KeyEvent.KEYCODE_SLEEP)) {
                SystemClock.sleep(800L);
                ok = !isAwake();
            }
        }
        if (!ok) {
            injectKey(SCAN_CODE_SLEEP);
        }
        Log.i(TAG, "screen sleep requested (ok=" + ok + ", state="
                + screenStateName() + ")");
    }

    /** Briefly lower the system screen-off timeout, wait for the framework's
     *  own timeline to sleep, and restore the original value. */
    private boolean timeoutTrickOnce() {
        long originalTimeout = -1;
        boolean canTrick = true;
        try {
            originalTimeout = Settings.System.getInt(
                    mApp.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 864000000);
        } catch (Throwable t) {
            canTrick = false;
            Log.d(TAG, "WRITE_SETTINGS timeout trick unavailable", t);
        }
        if (canTrick) {
            try {
                Settings.System.putInt(mApp.getContentResolver(),
                        Settings.System.SCREEN_OFF_TIMEOUT, SLEEP_TIMEOUT_MS);
            } catch (Throwable t) {
                canTrick = false;
                Log.d(TAG, "WRITE_SETTINGS timeout trick unavailable", t);
            }
        }
        boolean slept = false;
        if (canTrick) {
            long deadline = SystemClock.uptimeMillis() + SLEEP_WAIT_DEADLINE_MS;
            while (SystemClock.uptimeMillis() < deadline) {
                if (!isAwake()) {
                    slept = true;
                    break;
                }
                SystemClock.sleep(250L);
            }
        }
        if (originalTimeout >= 0) {
            try {
                Settings.System.putInt(mApp.getContentResolver(),
                        Settings.System.SCREEN_OFF_TIMEOUT, (int) originalTimeout);
            } catch (Throwable t) {
                Log.w(TAG, "could not restore screen_off_timeout", t);
            }
        }
        return slept;
    }

    /** The package holding a deprecated FULL_WAKE_LOCK, if any. */
    private String findFullWakeLockHolder() {
        String line = execShell("dumpsys power 2>/dev/null | grep -m1 'FULL_WAKE_LOCK'");
        int i = line.indexOf("uid=");
        if (i < 0) {
            return null;
        }
        i += 4;
        int end = i;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (end == i) {
            return null;
        }
        String uid = line.substring(i, end);
        try {
            if (Integer.parseInt(uid) == mApp.getApplicationInfo().uid) {
                return null; // us — don't force-stop ourselves
            }
        } catch (Exception ignored) {
        }
        String pkg = execShell("pm list packages -U 2>/dev/null | grep -m1 'uid:" + uid
                + "' | sed 's/package://' | cut -d' ' -f1");
        if (pkg.length() < 4) {
            return null;
        }
        if (pkg.equals(mApp.getPackageName())
                || pkg.startsWith("android.")
                || "com.amazon.platform".equals(pkg)) {
            return null;
        }
        return pkg;
    }

    private void forceStopPackage(String pkg) {
        forceStopPackageInternal(pkg);
    }

    private static void forceStopPackageInternal(String pkg) {
        try {
            int code = Runtime.getRuntime().exec(
                    new String[]{"am", "force-stop", pkg}).waitFor();
            Log.i(TAG, "force-stop " + pkg + " -> exit=" + code);
        } catch (Exception e) {
            Log.w(TAG, "force-stop " + pkg + " failed", e);
        }
    }

    private static String execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int r;
            while ((r = p.getInputStream().read(buf)) > 0) {
                if (bos.size() < 4096) {
                    bos.write(buf, 0, r);
                }
            }
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return bos.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ wake

    private boolean acquireWakeLock() {
        PowerManager.WakeLock lock = mPower == null ? null : mPower.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG);
        if (lock == null) {
            return false;
        }
        sLastWakeLock = lock;
        try {
            lock.acquire(WAKE_LOCK_MILLIS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "wake lock acquire failed", e);
            return false;
        }
    }

    /** Last FULL_WAKE_LOCK we took for a wake — a deprecated full lock keeps the
     *  screen on until released, so drop it before trying to sleep. */
    private static PowerManager.WakeLock sLastWakeLock;

    private void releaseOwnWakeLock() {
        PowerManager.WakeLock lock = sLastWakeLock;
        if (lock != null) {
            try {
                if (lock.isHeld()) {
                    lock.release();
                }
            } catch (Throwable ignored) {
            }
            sLastWakeLock = null;
        }
    }

    // IPowerManager.wakeUp(long, int, String, String) — needs DEVICE_POWER.
    private boolean binderWake() {
        try {
            Object binder = powerBinder();
            if (binder == null) {
                return false;
            }
            Method m = binder.getClass().getMethod("wakeUp",
                    long.class, int.class, String.class, String.class);
            m.invoke(binder, SystemClock.uptimeMillis(), 0, "motionwake", "com.egraf.motionwake");
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "IPowerManager.wakeUp unavailable", t);
            return false;
        }
    }

    // ----------------------------------------------------------------- sleep

    private boolean tryGoToSleep() {
        if (mPower == null) {
            return false;
        }
        // Client-side variants, if the build exposes them.
        Class<?> pmClass = PowerManager.class;
        try {
            Method m = pmClass.getMethod("goToSleep", int.class);
            m.invoke(mPower, 0);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "goToSleep(int) unavailable", t);
        }
        try {
            Method m = pmClass.getMethod("goToSleep", long.class, int.class);
            m.invoke(mPower, 0L, 0);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "goToSleep(long,int) unavailable", t);
        }
        try {
            Method m = pmClass.getMethod("goToSleep", long.class, int.class, int.class);
            m.invoke(mPower, SystemClock.uptimeMillis(), 0, 0);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "goToSleep(long,int,int) unavailable", t);
        }
        // Binder fallback: PowerManager.mService (IPowerManager stub),
        // signature per AOSP 11 IPowerManager.aidl; needs DEVICE_POWER.
        try {
            Object binder = powerBinder();
            if (binder == null) {
                return false;
            }
            Method m = binder.getClass().getMethod("goToSleep",
                    long.class, int.class, int.class);
            m.invoke(binder, SystemClock.uptimeMillis(), 0, 0);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "IPowerManager.goToSleep unavailable", t);
            return false;
        }
    }

    private Object powerBinder() throws Exception {
        java.lang.reflect.Field f = PowerManager.class.getDeclaredField("mService");
        f.setAccessible(true);
        return f.get(mPower);
    }

    // ------------------------------------------------------------- input key

    /**
     * Inject a key pair directly from the service context.
     *
     * This build allows injection WITHOUT INJECT_EVENTS as long as the
     * focused window belongs to our own uid (see the "Injecting to another
     * application requires INJECT_EVENTS" check in
     * InputManagerService.injectInputEvent). With the DashboardBrowser on
     * top we satisfy that exactly — and the event then targets the DASHBOARD
     * window, which does not consume the key; the framework processes the
     * unhandled KEYCODE_SLEEP/KEYCODE_WAKEUP and flips the screen (verified
     * on this build with 'input keyevent 223' / 'input keyevent 224').
     *
     * Returns true if the framework accepted both events.
     */
    private boolean injectKeyFromService(int keycode) {
        try {
            android.hardware.input.InputManager im =
                    (android.hardware.input.InputManager)
                            mApp.getSystemService(Context.INPUT_SERVICE);
            java.lang.reflect.Method m =
                    android.hardware.input.InputManager.class.getMethod(
                            "injectInputEvent", android.view.InputEvent.class, int.class);
            long t1 = SystemClock.uptimeMillis();
            Object dR = m.invoke(im, new android.view.KeyEvent(
                    t1, t1, android.view.KeyEvent.ACTION_DOWN, keycode, 0, 0, 0, 0),
                    1 /* INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT */);
            Thread.sleep(100L);
            long t2 = SystemClock.uptimeMillis();
            Object uR = m.invoke(im, new android.view.KeyEvent(
                    t2, t2, android.view.KeyEvent.ACTION_UP, keycode, 0, 0, 0, 0),
                    1);
            boolean ok = accepted(dR) && accepted(uR);
            Log.i(TAG, "service injection keycode " + keycode + " ok=" + ok
                    + " down=" + describe(dR) + " up=" + describe(uR));
            return ok;
        } catch (Throwable t) {
            Log.d(TAG, "service injection of keycode " + keycode + " unavailable", t);
            return false;
        }
    }

    /** interpret injectInputEvent results across API eras (Boolean or int). */
    private static boolean accepted(Object r) {
        if (r instanceof Boolean) {
            return (Boolean) r;
        }
        if (r instanceof Number) {
            return ((Number) r).intValue() == 0;
        }
        return false;
    }

    private static int describe(Object r) {
        if (r instanceof Boolean) {
            return (Boolean) r ? 0 : 1;
        }
        if (r instanceof Number) {
            return ((Number) r).intValue();
        }
        return -999;
    }

    private void injectKey(int scanCode) {
        for (int node = 0; node <= 7; node++) {
            File f = new File("/dev/input/event" + node);
            if (!f.canWrite()) {
                continue;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(inputEvent(EV_KEY, scanCode, 1)); // down
                out.write(inputEvent(EV_KEY, scanCode, 0)); // up
                out.write(inputEvent(EV_SYN, 0, 0));        // sync
                out.flush();
                Log.i(TAG, "injected scan code " + scanCode + " via " + f);
                return;
            } catch (Exception e) {
                Log.d(TAG, "inject via " + f + " failed", e);
            }
        }
        Log.w(TAG, "no writable /dev/input/event* node for scan code " + scanCode);
    }

    private static byte[] inputEvent(int type, int code, int value) {
        ByteBuffer bb = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0);              // tv_sec
        bb.putInt(0);              // tv_usec
        bb.putShort((short) type); // type
        bb.putShort((short) code); // code
        bb.putInt(value);          // value
        bb.putShort((short) 0);   // padding
        return bb.array();
    }
}
