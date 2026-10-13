/*
 * ScreenController — turns the display on/off from a service or receiver.
 *
 * Wake: FULL_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP (only needs WAKE_LOCK),
 * with raw KEY_WAKEUP input injection as a fallback.
 * Sleep: PowerManager.goToSleep() via reflection (system API, callable when
 * the WAKE_LOCK permission is held), with raw KEY_SLEEP injection fallback.
 */
package com.egraf.motionwake;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ScreenController {

    private static final String TAG = "ScreenControl";
    private static final String WAKE_LOCK_TAG = "motionwatch:wake";
    private static final long WAKE_LOCK_MILLIS = 5000L;

    // Linux input-event protocol (struct input_event), little-endian on ARM.
    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int SCAN_CODE_WAKEUP = 112; // KEY_WAKEUP
    private static final int SCAN_CODE_SLEEP = 122;  // KEY_SLEEP

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
        PowerManager.WakeLock lock = mPower == null ? null : mPower.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_LOCK_TAG);
        boolean acquired = false;
        if (lock != null) {
            try {
                lock.acquire(WAKE_LOCK_MILLIS); // self-releasing; holds screen on briefly
                acquired = true;
            } catch (Exception e) {
                Log.w(TAG, "wake lock acquire failed", e);
            }
        }
        if (!acquired) {
            injectKey(SCAN_CODE_WAKEUP);
        }
        Log.i(TAG, "screen wake requested");
    }

    void sleep() {
        boolean ok = tryGoToSleep();
        if (!ok) {
            injectKey(SCAN_CODE_SLEEP);
        }
        Log.i(TAG, "screen sleep requested");
    }

    private boolean tryGoToSleep() {
        if (mPower == null) {
            return false;
        }
        // goToSleep() is a system API, but apps holding WAKE_LOCK may call it.
        try {
            Method m = PowerManager.class.getMethod("goToSleep", int.class);
            m.invoke(mPower, 0);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "goToSleep(int) unavailable", t);
        }
        try {
            Method m = PowerManager.class.getMethod("goToSleep", long.class, int.class);
            m.invoke(mPower, 0L, 0);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "goToSleep unavailable — falling back to key event", t);
            return false;
        }
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
        bb.putInt(0);               // tv_sec
        bb.putInt(0);               // tv_usec
        bb.putShort((short) type);  // type
        bb.putShort((short) code);  // code
        bb.putInt(value);           // value
        bb.putShort((short) 0);    // padding
        return bb.array();
    }
}
