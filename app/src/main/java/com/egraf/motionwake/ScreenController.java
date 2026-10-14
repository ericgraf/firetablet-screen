/*
 * ScreenController — puts the display to sleep / wakes it from a service or
 * receiver. Multiple strategies because the API surface differs per build:
 *
 * Sleep (tried in order):
 *   1. IPowerManager.goToSleep(long,int,int) via reflection — works when the
 *      DEVICE_POWER permission is granted (privileged/system installs).
 *   2. KEYCODE_SLEEP via InputManager (needs INJECT_EVENTS; verified on this
 *      Fire build to beat the Fully launcher's deprecated FULL_WAKE_LOCK).
 *   3. "Short screen_off_timeout" dance: WRITE_SETTINGS lets us lower the
 *      system screen-off timeout to a few seconds and let PowerManagerService
 *      perform its own internal timeout sleep (works when nobody holds a
 *      deprecated full wake lock).
 *   4. If the screen still stays on, a deprecated FULL_WAKE_LOCK is holding it
 *      (on this tablet: the Fully launcher's "always on home"). Identify the
 *      holder from `dumpsys power`, force-stop it, and retry step 3 (needs
 *      the DUMP permission to read the holder).
 *   5. Raw KEY_SLEEP input-event injection (last resort; needs /dev/input).
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
    private static final long SLEEP_WAIT_DEADLINE_MS = 20000L;
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
    }

    boolean isScreenOn() {
        return mPower == null || mPower.isInteractive();
    }

    private int mInjectSeq = 0;

    /**
     * Inject a key event on a transparent KeyInjector activity (InputManager
     * allows injection without INJECT_EVENTS while our window is foreground).
     * Returns true if the event pair was accepted.
     */
    private boolean injectKeycode(int keycode) {
        final int seq = mInjectSeq++;
        try {
            android.content.Intent i = new android.content.Intent(mApp, KeyInjector.class);
            i.putExtra("seq", seq);
            i.putExtra("keycode", keycode);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            // Start the injector task BEHIND the foreground task (see the
            // note in KeyInjector.onCreate): Fire's focus walk then resolves
            // to a NULL focus window while the injector is alive, and
            // InputDispatcher accepts our events without INJECT_EVENTS.
            // (setLaunchTaskBehind is @hide in the SDK stubs — reflect it.)
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            try {
                java.lang.reflect.Method m =
                        android.app.ActivityOptions.class.getMethod("setLaunchTaskBehind", boolean.class);
                opts = (android.app.ActivityOptions) m.invoke(opts, true);
            } catch (Throwable ignored) {
            }
            mApp.startActivity(i, opts.toBundle());
            // The activity retries injection until its window is top (<=3s).
            long deadline = SystemClock.uptimeMillis() + 5000L;
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
        boolean on = isScreenOn();
        if (!on) {
            acquireWakeLock();
            SystemClock.sleep(150L);
            on = isScreenOn();
        }
        if (!on) {
            binderWake();
            SystemClock.sleep(300L);
            on = isScreenOn();
        }
        if (!on) {
            if (injectKeycode(KeyEvent.KEYCODE_WAKEUP)) {
                SystemClock.sleep(500L);
            }
            on = isScreenOn();
        }
        if (!on) {
            injectKey(SCAN_CODE_WAKEUP);
        }
        Log.i(TAG, "screen wake requested (on=" + on + ")");
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
        boolean ok = !isScreenOn();
        if (!ok) {
            ok = tryGoToSleep();
        }
        if (!ok) {
            // Verified on this Fire build: the forced-sleep key path also
            // beats the launcher's deprecated FULL_WAKE_LOCK (Fully).
            if (injectKeycode(KeyEvent.KEYCODE_SLEEP)) {
                SystemClock.sleep(800L);
                ok = !isScreenOn();
            }
        }
        if (!ok) {
            ok = timeoutTrickOnce();
        }
        if (!ok) {
            // A deprecated FULL_WAKE_LOCK legitimately keeps the screen on
            // (typical offender on this tablet: the Fully launcher, which uses
            // it for its "always on" home). Kill the holder and retry the trick.
            String victim = findFullWakeLockHolder();
            if (victim != null) {
                Log.i(TAG, "sleep: force-stopping FULL_WAKE_LOCK holder " + victim);
                forceStopPackage(victim);
                SystemClock.sleep(1000L); // let the holder release its locks
                ok = timeoutTrickOnce();
            }
        }
        if (!ok) {
            injectKey(SCAN_CODE_SLEEP);
        }
        Log.i(TAG, "screen sleep requested (ok=" + ok + ")");
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
                if (!isScreenOn()) {
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
        try {
            lock.acquire(WAKE_LOCK_MILLIS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "wake lock acquire failed", e);
            return false;
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
