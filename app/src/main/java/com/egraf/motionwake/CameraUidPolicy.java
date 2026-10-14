/*
 * CameraUidPolicy — keep the camera service's per-UID policy "active" for us.
 *
 * On this Fire build the native cameraserver enforces a UidPolicy: when the
 * system considers our UID "idle" (no visible activity — exactly the state a
 * motion-watch tablet is in while the screen sleeps), camera opens are
 * rejected with "Camera '0' disabled by policy" (CAMERA_DISABLED). The
 * cameraserver exposes an overrides table:
 *
 *   dumpsys media.camera set-uid-state <PACKAGE> active
 *
 * which is in-memory (lost when cameraserver restarts or on reboot). So we
 * (re)apply it at service start and again once our camera cycles start
 * failing — that second apply covers cameraserver restarts.
 */
package com.egraf.motionwake;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

final class CameraUidPolicy {

    private static final String TAG = "MotionCamera";
    private static final String PKG = "com.egraf.motionwake";
    // don't hammer the cameraserver if it keeps rejecting us
    private static final long MIN_APPLY_INTERVAL_MS = 5000L;

    private static long sLastApplyMs;
    private static final Object LOCK = new Object();

    /**
     * Ask the camera service to remember our uid as active. Returns true if
     * the command ran (exit 0). Safe to call from any thread.
     */
    static boolean ensureActive(Context app) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - sLastApplyMs < MIN_APPLY_INTERVAL_MS) {
                return true;
            }
            sLastApplyMs = now;
        }
        Process p = null;
        try {
            p = new ProcessBuilder(
                    "sh", "-c",
                    "dumpsys media.camera set-uid-state " + PKG + " active"
                            + " >/dev/null 2>&1; echo rc=$?")
                    .redirectErrorStream(true)
                    .start();
            String out = readAll(p.getInputStream());
            boolean ok = p.waitFor(8L, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                p.destroy();
            }
            int rc = 0;
            try {
                rc = p.exitValue();
            } catch (IllegalStateException e) {
                return false; // still running
            }
            Log.i(TAG, "camera uid-state override applied (rc=" + rc + ", " + out.trim() + ")");
            return rc == 0;
        } catch (Exception e) {
            Log.w(TAG, "camera uid-state override failed", e);
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readAll(InputStream in) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = r.readLine()) != null && sb.length() < 200) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
