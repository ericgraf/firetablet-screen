/*
 * MotionCameraManager — duty-cycled Camera2 motion detection (power saver).
 *
 * Instead of holding a continuous preview (which keeps the sensor and DSP
 * running 24/7 and warms the tablet), short check cycles are run:
 *
 *   open camera → small preview session → wait for auto-exposure to settle
 *   → grab one still → close the camera → compare with the previous check
 *   → wait the check interval (default 4 s, configurable 2–30 s) → repeat.
 *
 * Camera on-time per cycle ≈ 1–2 s against an off period of several seconds,
 * so the sensor is on ~25–40% of the time instead of 100%. Motion = the mean
 * absolute luma difference versus the previous check at/above the threshold
 * for CONFIRM_CYCLES consecutive checks (after WARMUP_CYCLES warm-up checks
 * while auto-exposure settles). Failed checks (camera busy, timeouts) pace
 * the next check in with backoff.
 *
 * All Camera2 callbacks run on one dedicated HandlerThread.
 */
package com.egraf.motionwake;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.Collections;
import java.util.Locale;

final class MotionCameraManager {

    interface MotionListener {
        void onMotionDetected();
    }

    private static final String TAG = "MotionCamera";

    // check cadence (power saving)
    static final long DEFAULT_INTERVAL_MS = 4000L;
    private static final long MIN_INTERVAL_MS = 2000L;
    private static final long MAX_INTERVAL_MS = 30000L;
    // per-cycle timing
    private static final long SETTLE_MS = 1000L;      // auto-exposure settling before the grab
    private static final long OPEN_TIMEOUT_MS = 2000L;
    private static final long CONFIG_TIMEOUT_MS = 2500L;
    // failure pacing: retry sooner than the normal interval, capped
    private static final long FAIL_BASE_MS = 2000L;
    private static final long FAIL_MAX_MS = 15000L;
    private static final float FAIL_BACKOFF = 1.6f;
    // motion decision
    private static final int CONFIRM_CYCLES = 2;
    private static final int WARMUP_CYCLES = 2;
    // luma grid for differencing (multiple of 4 on both axes)
    private static final int GRID_W = 96;
    private static final int GRID_H = 72;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_OPEN = 1;
    private static final int PHASE_CONFIG = 2;
    private static final int PHASE_SETTLE = 3;

    private final Context mApp;
    private final MotionListener mListener;

    private HandlerThread mThread;
    private Handler mHandler;
    private CameraManager mCameraManager;

    private volatile boolean mRunning = false;
    private volatile long mIntervalMs = DEFAULT_INTERVAL_MS;
    private volatile float mThreshold = MotionWakeService.DEFAULT_THRESHOLD;
    private int mPhase = PHASE_IDLE; // camera thread only

    // decision state — camera thread only
    private int mCycleCount;
    private int mConfirmed;
    private float[] mPrevGrid;
    private int mFailCount;

    // in-flight resources — camera thread only
    private CameraDevice mDevice;
    private CameraCaptureSession mSession;
    private ImageReader mReader;

    // per-phase timeout guards — posted/removed on the camera handler only
    private final Runnable mOpenTimeout = new Runnable() {
        @Override
        public void run() {
            if (mPhase == PHASE_OPEN) {
                Log.w(TAG, "cycle: camera open timeout");
                closeCameraInternal();
                failCycle("open timeout");
            }
        }
    };

    private final Runnable mConfigTimeout = new Runnable() {
        @Override
        public void run() {
            if (mPhase == PHASE_CONFIG) {
                Log.w(TAG, "cycle: session configure timeout");
                closeCameraInternal();
                failCycle("configure timeout");
            }
        }
    };

    MotionCameraManager(Context app, MotionListener listener) {
        mApp = app.getApplicationContext();
        mListener = listener;
    }

    float getThreshold() {
        return mThreshold;
    }

    void setThreshold(float threshold) {
        mThreshold = threshold;
    }

    long getIntervalMs() {
        return mIntervalMs;
    }

    void setIntervalMs(long v) {
        mIntervalMs = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, v));
    }

    synchronized void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mCycleCount = 0;
        mConfirmed = 0;
        mPrevGrid = null;
        mFailCount = 0;
        mPhase = PHASE_IDLE;
        mThread = new HandlerThread("motionwatch-camera");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mCameraManager = (CameraManager) mApp.getSystemService(Context.CAMERA_SERVICE);
        post(new Runnable() {
            @Override
            public void run() {
                // This Fire build's cameraserver rejects camera opens from
                // UIDs the system considers idle (screen off + no visible
                // activity) — make sure our override is in place first.
                CameraUidPolicy.ensureActive(mApp);
                // The same policy also refuses UIDs without a visible
                // foreground activity. At boot the service starts in the
                // background, before home resolves — bring the dashboard
                // (our home activity) up so the camera can connect.
                bringDashboardToFront("monitor start");
                beginCycle();
            }
        });
    }

    synchronized void stop() {
        if (!mRunning && mThread == null) {
            return;
        }
        mRunning = false;
        // quitSafely still runs the pending cycle messages, which close the
        // camera cleanly, then the thread exits.
        if (mThread != null) {
            mThread.quitSafely();
            try {
                mThread.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeCameraInternal();
        mThread = null;
        mHandler = null;
        mPhase = PHASE_IDLE;
    }

    // ------------------------------------------------------------------ cycle

    // Runs on the camera handler thread — one step of the state machine.
    private void beginCycle() {
        if (!mRunning) {
            return;
        }
        if (mPhase != PHASE_IDLE) {
            scheduleNext();
            return;
        }
        if (mApp.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            failCycle("CAMERA permission not granted yet");
            return;
        }
        if (mCameraManager == null) {
            failCycle("camera manager missing");
            return;
        }
        String id;
        try {
            id = pickCameraId();
        } catch (CameraAccessException e) {
            failCycle("camera enumeration failed");
            return;
        }
        if (id == null) {
            failCycle("no usable camera found");
            return;
        }
        final String cameraId = id;
        mPhase = PHASE_OPEN;
        mHandler.postDelayed(mOpenTimeout, OPEN_TIMEOUT_MS);
        try {
            mCameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (mPhase != PHASE_OPEN) {
                        camera.close();
                        return;
                    }
                    mHandler.removeCallbacks(mOpenTimeout);
                    mDevice = camera;
                    configureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    failPhaseCallback("camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    failPhaseCallback("camera error " + error);
                }
            }, mHandler);
        } catch (CameraAccessException e) {
            Log.w(TAG, "openCamera failed", e);
            closeCameraInternal();
            failCycle("openCamera exception");
        }
    }

    // Runs on the camera handler thread.
    private void configureSession() {
        if (mPhase != PHASE_OPEN || mDevice == null) {
            return;
        }
        int[] wh;
        try {
            wh = pickYuvSize();
        } catch (CameraAccessException e) {
            closeCameraInternal();
            failCycle("size lookup failed");
            return;
        }
        final int w = wh[0];
        final int h = wh[1];
        mPhase = PHASE_CONFIG;
        mHandler.postDelayed(mConfigTimeout, CONFIG_TIMEOUT_MS);
        try {
            mReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4);
            CaptureRequest.Builder builder =
                    mDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(mReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            try {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(10, 30));
            } catch (IllegalArgumentException ignored) {
            }
            final CaptureRequest request = builder.build();
            mDevice.createCaptureSession(
                    Collections.singletonList(mReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (mPhase != PHASE_CONFIG) {
                                session.close();
                                return;
                            }
                            mHandler.removeCallbacks(mConfigTimeout);
                            mSession = session;
                            try {
                                session.setRepeatingRequest(request, null, mHandler);
                            } catch (Exception e) {
                                closeCameraInternal();
                                failCycle("setRepeatingRequest failed");
                                return;
                            }
                            // Let auto-exposure settle, then grab one still.
                            mPhase = PHASE_SETTLE;
                            mHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (mPhase != PHASE_SETTLE) {
                                        return;
                                    }
                                    Image image = mReader != null
                                            ? mReader.acquireLatestImage() : null;
                                    float[] grid = null;
                                    if (image != null) {
                                        try {
                                            grid = extractGrid(image);
                                        } catch (Exception e) {
                                            Log.w(TAG, "grab failed", e);
                                        } finally {
                                            image.close();
                                        }
                                    }
                                    closeCameraInternal();
                                    succeedCycle(grid);
                                }
                            }, SETTLE_MS);
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            if (mPhase == PHASE_CONFIG) {
                                closeCameraInternal();
                                failCycle("session configure failed");
                            } else {
                                session.close();
                            }
                        }
                    },
                    mHandler);
        } catch (Exception e) {
            Log.w(TAG, "configureSession failed", e);
            closeCameraInternal();
            failCycle("configureSession exception");
        }
    }

    /** Camera disconnected/error during open/config — settle phase owns itself. */
    private void failPhaseCallback(String reason) {
        if (mPhase == PHASE_SETTLE || mPhase == PHASE_IDLE) {
            return;
        }
        closeCameraInternal();
        failCycle(reason);
    }

    private void succeedCycle(float[] grid) {
        if (grid == null) {
            failCycle("no image grabbed");
            return;
        }
        mFailCount = 0;
        mCycleCount++;
        if (mCycleCount <= WARMUP_CYCLES) {
            mPrevGrid = grid;
        } else {
            if (mPrevGrid != null) {
                float diff = meanAbsDiff(grid, mPrevGrid);
                Log.d(TAG, String.format(Locale.US, "cycle %d diff=%.2f thr=%.1f",
                        mCycleCount, diff, mThreshold));
                if (diff >= mThreshold) {
                    mConfirmed++;
                    if (mConfirmed >= CONFIRM_CYCLES) {
                        mConfirmed = 0;
                        Log.i(TAG, "MOTION DETECTED (diff="
                                + String.format(Locale.US, "%.2f", diff) + ")");
                        mListener.onMotionDetected();
                    }
                } else {
                    mConfirmed = 0;
                }
            }
            mPrevGrid = grid;
        }
        mPhase = PHASE_IDLE;
        scheduleNext();
    }

    private void failCycle(String reason) {
        mFailCount++;
        Log.w(TAG, "cycle failed (" + reason + "), retry # " + mFailCount);
        if (mFailCount >= 3 && (mFailCount == 3 || mFailCount % 10 == 0)) {
            // Re-apply the camera UidPolicy override (e.g. cameraserver
            // restarted and lost its in-memory override table). Throttled
            // inside ensureActive() to once per 5s.
            CameraUidPolicy.ensureActive(mApp);
        }
        if ((reason.startsWith("openCamera") || reason.startsWith("camera "))
                && (mFailCount == 5 || mFailCount % 15 == 0)) {
            // "disabled by policy": our UID has no visible foreground
            // activity (service started in the background at boot, or
            // another app took the screen over). Bring the dashboard —
            // our home activity — back to the front so the camera can
            // connect. Relaunch is cheap (singleTask, reused in place).
            bringDashboardToFront(reason);
        }
        mPhase = PHASE_IDLE;
        scheduleNext();
    }

    /**
     * Bring the kiosk dashboard to the front. This Fire build's
     * background-camera policy refuses camera connects for UIDs without a
     * visible activity, so a foreground instance of our own activity is
     * what actually unblocks the sensor (the set-uid-state override alone
     * did not). Safe from any thread; no-op-ish when already on top.
     */
    private void bringDashboardToFront(String why) {
        if (!mRunning) {
            return;
        }
        Log.i(TAG, "self-heal: bringing dashboard to front (" + why + ")");
        try {
            Intent intent = new Intent(mApp, DashboardBrowser.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mApp.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "self-heal dashboard launch failed", e);
        }
    }

    private void scheduleNext() {
        if (!mRunning) {
            return;
        }
        long delay;
        if (mFailCount == 0) {
            delay = mIntervalMs;
        } else {
            long d = FAIL_BASE_MS;
            for (int i = 1; i < mFailCount && d < FAIL_MAX_MS; i++) {
                d = Math.min(FAIL_MAX_MS, (long) (d * FAIL_BACKOFF));
            }
            delay = Math.min(mIntervalMs, d);
        }
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                beginCycle();
            }
        }, delay);
    }

    // ---------------------------------------------------------------- helpers

    private void post(Runnable r) {
        Handler h = mHandler;
        if (h != null) {
            h.post(r);
        }
    }

    private void closeCameraInternal() {
        if (mSession != null) {
            try {
                mSession.close();
            } catch (Exception ignored) {
            }
            mSession = null;
        }
        if (mReader != null) {
            try {
                mReader.close();
            } catch (Exception ignored) {
            }
            mReader = null;
        }
        if (mDevice != null) {
            try {
                mDevice.close();
            } catch (Exception ignored) {
            }
            mDevice = null;
        }
    }

    private static float meanAbsDiff(float[] a, float[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.round(Math.abs(a[i] - b[i]));
        }
        return (float) sum / a.length;
    }

    private float[] extractGrid(Image image) {
        Image.Plane plane = image.getPlanes()[0]; // Y plane
        java.nio.ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int w = image.getWidth();
        int h = image.getHeight();
        float[] grid = new float[GRID_W * GRID_H];
        int idxLimit = buffer.limit();
        for (int gy = 0; gy < GRID_H; gy++) {
            int y = gy * h / GRID_H;
            int base = y * rowStride;
            int rowBase = gy * GRID_W;
            for (int gx = 0; gx < GRID_W; gx++) {
                int x = gx * w / GRID_W;
                int idx = base + x * pixelStride;
                if (idx < idxLimit) {
                    grid[rowBase + gx] = (buffer.get(idx)) & 0xFF;
                } else {
                    grid[rowBase + gx] = 0f;
                }
            }
        }
        return grid;
    }

    private String pickCameraId() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();
        if (ids == null || ids.length == 0) {
            return null;
        }
        String fallback = ids[0];
        for (String id : ids) {
            try {
                CameraCharacteristics ch = mCameraManager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            } catch (CameraAccessException ignored) {
            }
        }
        return fallback;
    }

    private int[] pickYuvSize() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();
        String id = ids != null && ids.length > 0 ? ids[0] : null;
        if (id != null) {
            CameraCharacteristics ch = mCameraManager.getCameraCharacteristics(id);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    ch != null ? ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) : null;
            Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.YUV_420_888) : null;
            if (sizes != null && sizes.length > 0) {
                Size best = null;
                long bestArea = Long.MAX_VALUE;
                for (Size s : sizes) {
                    if (s.getWidth() < GRID_W || s.getHeight() < GRID_H) {
                        continue;
                    }
                    long area = (long) s.getWidth() * (long) s.getHeight();
                    if (best == null || area < bestArea) {
                        best = s;
                        bestArea = area;
                    }
                }
                if (best != null) {
                    return new int[]{best.getWidth(), best.getHeight()};
                }
            }
        }
        return new int[]{640, 480};
    }
}
