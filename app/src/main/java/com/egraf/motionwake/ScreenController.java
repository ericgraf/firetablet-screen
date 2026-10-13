/*
 * ScreenController — puts the display to sleep / wakes it from a service or
 * receiver. Multiple strategies because the API surface differs per build:
 *
 * Sleep (tried in order):
 *   1. IPowerManager.goToSleep(long,int,int) via reflection — works when the
 *      DEVICE_POWER permission is granted (privileged/system installs).
 *   2. "Short screen_off_timeout" dance: WRITE_SETTINGS lets us lower the
 *      system screen-off timeout to a few seconds and let PowerManagerService
 *      perform its own internal timeout sleep (no special permission needed,
 *      works on Fire OS / AOSP builds where DEVICE_POWER is unreachable).
 *   3. Raw KEY_SLEEP input-event injection (last resort; needs /dev/input).
 *
 * Wake (tried in order):
 *   1. FULL_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP (only needs WAKE_LOCK).
 *   2. IPowerManager.wakeUp(...) via reflection (needs DEVICE_POWER).
 *   3. Raw KEY_WAKEUP input-event injection.
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

    void wake() {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = acquireWakeLock();
                if (!ok) {
                    ok = binderWake();
                }
                if (!ok) {
                    injectKey(SCAN_CODE_WAKEUP);
                }
                Log.i(TAG, "screen wake requested");
            }
        });
    }

    void sleep() {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = tryGoToSleep();
                long originalTimeout = -1;
                if (!ok) {
                    try {
                        ContentResolver cr = mApp.getContentResolver();
                        originalTimeout = Settings.System.getInt(cr,
                                Settings.System.SCREEN_OFF_TIMEOUT, 864000000);
                    } catch (Throwable ignored) {
                    }
                }
                if (!ok) {
                    try {
                        Settings.System.putInt(mApp.getContentResolver(),
                                Settings.System.SCREEN_OFF_TIMEOUT, SLEEP_TIMEOUT_MS);
                    } catch (Throwable t) {
                        Log.d(TAG, "WRITE_SETTINGS timeout trick unavailable", t);
                    }
                }
                if (!ok) {
                    long deadline = SystemClock.uptimeMillis() + SLEEP_WAIT_DEADLINE_MS;
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (!isScreenOn()) {
                            ok = true;
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
                if (!ok) {
                    injectKey(SCAN_CODE_SLEEP);
                }
                Log.i(TAG, "screen sleep requested (ok=" + ok + ")");
            }
        });
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
