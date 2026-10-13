/*
 * MotionCameraManager — Camera2 preview pipeline for motion detection.
 *
 * Opens the back camera (front as fallback) at a small YUV_420_888 preview
 * size, downsamples each frame's luma plane to a fixed GRID_W x GRID_H
 * grid, and flags "motion" when the mean absolute difference between
 * successive frames stays at/above the threshold for CONFIRM_FRAMES
 * consecutive processed frames.
 *
 * All Camera2 callbacks run on a dedicated HandlerThread. Open failures
 * (permission not granted yet, device busy, ...) are retried with
 * backoff: 2s, then x1.6 up to 15s.
 */
package com.egraf.motionwake;

import android.Manifest;
import android.content.Context;
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
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class MotionCameraManager {

    interface MotionListener {
        void onMotionDetected();
    }

    private static final String TAG = "MotionCamera";

    // Luma grid used for frame differencing (multiple of 4 on both axes).
    private static final int GRID_W = 96;
    private static final int GRID_H = 72;
    // Consecutive frames over threshold before declaring motion.
    private static final int CONFIRM_FRAMES = 2;
    // Ignore first frames while the image signal settles.
    private static final int WARMUP_FRAMES = 5;
    // Cap processing rate — the camera delivers far more frames than needed.
    private static final long MIN_FRAME_INTERVAL_MS = 100;

    private static final long MIN_RETRY_MS = 2000L;
    private static final long MAX_RETRY_MS = 15000L;
    private static final float BACKOFF = 1.6f;

    private final Context mApp;
    private final MotionListener mListener;

    private HandlerThread mThread;
    private Handler mHandler;
    private CameraManager mCameraManager;
    private String mCameraId;
    private CameraDevice mDevice;
    private CameraCaptureSession mSession;
    private ImageReader mReader;

    private final Semaphore mOpenLock = new Semaphore(1);
    private volatile boolean mRunning = false;
    private volatile float mThreshold = MotionWakeService.DEFAULT_THRESHOLD;
    private volatile boolean mRetrying = false;
    private long mRetryDelayMs = MIN_RETRY_MS;

    // Differencing state — only touched on the camera handler thread.
    private float[] mPrevGrid;
    private int mConfirmed;
    private int mFramesSeen;
    private long mNextProcessAt;

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

    synchronized void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mRetrying = false;
        mRetryDelayMs = MIN_RETRY_MS;
        mPrevGrid = null;
        mConfirmed = 0;
        mFramesSeen = 0;
        mThread = new HandlerThread("motionwatch-camera");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mCameraManager = (CameraManager) mApp.getSystemService(Context.CAMERA_SERVICE);
        postCamera(new Runnable() {
            @Override
            public void run() {
                openCameraInternal();
            }
        });
    }

    synchronized void stop() {
        if (!mRunning && mThread == null) {
            return;
        }
        mRunning = false;
        mRetrying = false;
        closeCameraInternal();
        if (mThread != null) {
            mThread.quitSafely();
            try {
                mThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mThread = null;
            mHandler = null;
        }
    }

    private void postCamera(Runnable r) {
        Handler h = mHandler;
        if (h != null) {
            h.post(r);
        }
    }

    // Runs on the camera handler thread.
    private void openCameraInternal() {
        if (!mRunning) {
            return;
        }
        if (mApp.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            scheduleRetry("CAMERA permission not granted yet");
            return;
        }
        String id;
        try {
            id = pickCameraId();
        } catch (CameraAccessException e) {
            scheduleRetry("camera enumeration failed");
            return;
        }
        if (id == null) {
            scheduleRetry("no usable camera found");
            return;
        }
        mCameraId = id;

        if (!mOpenLock.tryAcquire()) {
            scheduleRetry("camera open lock busy");
            return;
        }
        try {
            mCameraManager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    mDevice = camera;
                    unlockCamera();
                    mRetryDelayMs = MIN_RETRY_MS;
                    postCamera(new Runnable() {
                        @Override
                        public void run() {
                            configureSession();
                        }
                    });
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    unlockCamera();
                    if (camera != null) {
                        camera.close();
                    }
                    if (mDevice == camera) {
                        mDevice = null;
                    }
                    if (mSession != null) {
                        try {
                            mSession.close();
                        } catch (Exception ignored) {
                        }
                        mSession = null;
                    }
                    scheduleRetry("camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    unlockCamera();
                    if (camera != null) {
                        camera.close();
                    }
                    if (mDevice == camera) {
                        mDevice = null;
                    }
                    scheduleRetry("camera error " + error);
                }
            }, mHandler);
        } catch (CameraAccessException e) {
            unlockCamera();
            Log.w(TAG, "openCamera failed", e);
            scheduleRetry("openCamera exception");
        }
    }

    /** Releases the camera-open lock at most once (callbacks can fire late). */
    private void unlockCamera() {
        if (!mOpenLock.tryRelease()) {
            // Already released — expected on late-disconnect paths.
        }
    }

    // Runs on the camera handler thread.
    private void configureSession() {
        if (!mRunning || mDevice == null) {
            return;
        }
        try {
            int[] wh = pickYuvSize(mCameraId);
            int w = wh[0];
            int h = wh[1];
            mReader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3);
            mReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    if (image == null) {
                        return;
                    }
                    try {
                        processFrame(image);
                    } finally {
                        image.close();
                    }
                }
            }, mHandler);

            CaptureRequest.Builder builder =
                    mDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(mReader);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, 0); // MANUAL
            try {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(10, 30));
            } catch (IllegalArgumentException ignored) {
            }

            mDevice.createCaptureSession(
                    Collections.singletonList(mReader),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (!mRunning) {
                                return;
                            }
                            mSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, mHandler);
                                mConfirmed = 0;
                                mFramesSeen = 0;
                                mPrevGrid = null;
                                Log.i(TAG, "camera ready (" + w + "x" + h + ")");
                            } catch (Exception e) {
                                Log.w(TAG, "setRepeatingRequest failed", e);
                                scheduleRetry("repeating request failed");
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            scheduleRetry("session configure failed");
                        }
                    },
                    mHandler);
        } catch (Exception e) {
            Log.w(TAG, "configureSession failed", e);
            scheduleRetry("configureSession exception");
        }
    }

    // Runs on the camera handler thread.
    private void processFrame(Image image) {
        long now = SystemClock.elapsedRealtime();
        if (now < mNextProcessAt) {
            return;
        }
        mNextProcessAt = now + MIN_FRAME_INTERVAL_MS;

        float[] grid = extractGrid(image);
        mFramesSeen++;
        if (mPrevGrid == null || mFramesSeen <= WARMUP_FRAMES) {
            mPrevGrid = grid;
            return;
        }
        long sum = 0;
        for (int i = 0; i < grid.length; i++) {
            sum += Math.round(Math.abs(grid[i] - mPrevGrid[i]));
        }
        float meanDiff = (float) sum / grid.length;
        if (meanDiff >= mThreshold) {
            mConfirmed++;
            if (mConfirmed >= CONFIRM_FRAMES) {
                mConfirmed = 0;
                mListener.onMotionDetected();
            }
        } else {
            mConfirmed = 0;
        }
        mPrevGrid = grid;
    }

    // Runs on the camera handler thread.
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

    private int[] pickYuvSize(String id) throws CameraAccessException {
        CameraCharacteristics ch = mCameraManager.getCameraCharacteristics(id);
        Size[] sizes = null;
        if (ch != null) {
            android.util.SizeMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            }
        }
        if (sizes != null && sizes.length > 0) {
            Size best = null;
            long bestArea = Long.MAX_VALUE;
            // Prefer the smallest size that still covers the grid: fast + enough detail.
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
        return new int[]{640, 480};
    }

    private void scheduleRetry(String reason) {
        if (!mRunning) {
            return;
        }
        if (mRetrying) {
            return;
        }
        mRetrying = true;
        long delay = mRetryDelayMs;
        mRetryDelayMs = Math.min(MAX_RETRY_MS, (long) (mRetryDelayMs * BACKOFF));
        Log.w(TAG, "camera retry in " + delay + "ms: " + reason);
        final Handler h = mHandler;
        if (h == null) {
            mRetrying = false;
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!mRunning) {
                    mRetrying = false;
                    return;
                }
                mRetrying = false;
                closeCameraInternal();
                openCameraInternal();
            }
        }, delay);
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
}
