/*
 * MotionWakeService — background service that watches the camera for motion,
 * sleeps the screen after a configurable idle period, and wakes the screen
 * when motion is detected.
 */
package com.egraf.motionwake;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

public final class MotionWakeService extends Service implements MotionCameraManager.MotionListener {

    public static final String TAG = "MotionWakeService";
    public static final String CHANNEL_ID = "motionwatch";
    public static final String PREFS_NAME = "motionwatch";
    public static final String PREF_IDLE_MINUTES = "idle_minutes";
    public static final String PREF_THRESHOLD = "threshold";
    public static final String PREF_AUTO_START = "auto_start";

    public static final float DEFAULT_THRESHOLD = 3f;
    public static final float MIN_THRESHOLD = 0.5f;
    public static final float MAX_THRESHOLD = 15f;
    public static final long DEFAULT_IDLE_MINUTES = 10;

    public static final String ACTION_START = "com.egraf.motionwake.action.START_SERVICE";
    public static final String ACTION_STOP = "com.egraf.motionwake.action.STOP_SERVICE";
    public static final String ACTION_WAKE = "com.egraf.motionwake.action.WAKE";
    public static final String ACTION_SLEEP = "com.egraf.motionwake.action.SLEEP";

    /** For the UI: whether the monitor loop is active. */
    public static volatile boolean sRunning = false;
    /** For the UI: wall time (ms) of the last motion event; 0 = not seen yet. */
    public static volatile long sLastMotionWall = 0L;
    /** For the UI: current milliseconds of inactivity. */
    public static volatile long sIdleMs = 0L;

    private static long sLastMotionAt = -1L; // SystemClock.elapsedRealtime(), service-private

    private final Object mStateLock = new Object();
    private MotionCameraManager mCamera;
    private PowerManager mPower;
    private PowerManager.WakeLock mPartialLock;
    private Handler mTick;
    private SharedPreferences mPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener;
    private long mIdleMs = DEFAULT_IDLE_MINUTES * 60 * 1000L;
    private volatile boolean mForegroundStarted;
    private volatile boolean mStopped;

    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sRunning) {
                return;
            }
            tickOnce();
            if (mTick != null) {
                mTick.postDelayed(this, 1000L);
            }
        }
    };

    /** Resets the idle timer, usable from receivers even while the service is stopped. */
    public static void touchMotion() {
        sLastMotionAt = SystemClock.elapsedRealtime();
        sLastMotionWall = System.currentTimeMillis();
        sIdleMs = 0L;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPower = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        mPrefs.edit()
                .putDefault(PREF_IDLE_MINUTES, DEFAULT_IDLE_MINUTES)
                .putDefault(PREF_THRESHOLD, DEFAULT_THRESHOLD)
                .putDefault(PREF_AUTO_START, true)
                .commit();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "STOP action — shutting down");
            stopMonitoring();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_WAKE.equals(action)) {
            touchMotion();
            new ScreenController(this).wake();
            return START_STICKY;
        }
        if (ACTION_SLEEP.equals(action)) {
            new ScreenController(this).sleep();
            return START_STICKY;
        }

        // ACTION_START (or no action): the service is started.
        // startForeground() must be called within ~5s of startForegroundService().
        ensureForeground();
        startMonitoring();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        super.onDestroy();
    }

    private void ensureForeground() {
        synchronized (this) {
            if (mForegroundStarted) {
                return;
            }
            mForegroundStarted = true;
        }
        createChannel();
        Notification notification = buildNotification();
        int type = 0;
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, 1, notification, type);
        } else {
            startForeground(1, notification);
        }
    }

    private void startMonitoring() {
        synchronized (mStateLock) {
            if (mStopped || mCamera != null) {
                return; // already running
            }
            mStopped = false;
            applyPrefs();
            if (mPrefListener == null) {
                mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                        if (PREF_THRESHOLD.equals(key)) {
                            if (mCamera != null) {
                                mCamera.setThreshold(clamp(prefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD),
                                        MIN_THRESHOLD, MAX_THRESHOLD));
                            }
                        } else if (PREF_IDLE_MINUTES.equals(key)) {
                            mIdleMs = idleMsFromPrefs(prefs);
                        }
                    }
                };
                mPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);
            }
            mPartialLock = mPower.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motionwatch:cpu");
            mPartialLock.acquire(24L * 60 * 60 * 1000L); // hard 24h safety cap
            mCamera = new MotionCameraManager(this, this);
            mCamera.setThreshold(clamp(mPrefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD),
                    MIN_THRESHOLD, MAX_THRESHOLD));
            mCamera.start();
        }
        if (mTick == null) {
            mTick = new Handler(Looper.getMainLooper());
        }
        mTick.post(mTickRunnable);
        sRunning = true;
        Log.i(TAG, "monitor started (idle=" + (mIdleMs / 60000) + "min, threshold=" + mCamera.getThreshold() + ")");
    }

    private void stopMonitoring() {
        synchronized (mStateLock) {
            mStopped = true;
            if (mTick != null) {
                mTick.removeCallbacks(mTickRunnable);
            }
            if (mCamera != null) {
                mCamera.stop();
                mCamera = null;
            }
            if (mPartialLock != null) {
                if (mPartialLock.isHeld()) {
                    mPartialLock.release();
                }
                mPartialLock = null;
            }
        }
        if (mPrefListener != null && mPrefs != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
            mPrefListener = null;
        }
        sRunning = false;
    }

    private void applyPrefs() {
        mIdleMs = idleMsFromPrefs(mPrefs);
    }

    static long idleMsFromPrefs(SharedPreferences prefs) {
        long minutes = prefs.getLong(PREF_IDLE_MINUTES, DEFAULT_IDLE_MINUTES);
        minutes = Math.max(1L, Math.min(minutes, 1440L));
        return minutes * 60 * 1000L;
    }

    private void tickOnce() {
        long idle = idleNowMs();
        sIdleMs = idle;
        if (idle >= mIdleMs && isInteractive()) {
            Log.i(TAG, "idle for " + (idle / 1000) + "s — putting screen to sleep");
            new ScreenController(this).sleep();
        }
    }

    private long idleNowMs() {
        long last = sLastMotionAt;
        if (last < 0) {
            return 0L;
        }
        return SystemClock.elapsedRealtime() - last;
    }

    private boolean isInteractive() {
        return mPower == null || mPower.isInteractive();
    }

    @Override
    public void onMotionDetected() {
        touchMotion();
        if (!isInteractive()) {
            Log.i(TAG, "motion while screen off — waking display");
            new ScreenController(this).wake();
        }
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL_ID, "Motion watch", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent wake = new Intent(this, ControlReceiver.class).setAction(ACTION_WAKE);
        Intent sleep = new Intent(this, ControlReceiver.class).setAction(ACTION_SLEEP);
        Intent stop = new Intent(this, ControlReceiver.class).setAction(ACTION_STOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        int icon = R.drawable.ic_stat_motion;
        String title = getString(R.string.app_name);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(getString(R.string.notif_body))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_body_big)))
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(new NotificationCompat.Action(icon, getString(R.string.action_wake), PendingIntent.getBroadcast(this, 1, wake, piFlags)))
                .addAction(new NotificationCompat.Action(icon, getString(R.string.action_sleep), PendingIntent.getBroadcast(this, 2, sleep, piFlags)))
                .addAction(new NotificationCompat.Action(icon, getString(R.string.action_stop), PendingIntent.getBroadcast(this, 3, stop, piFlags)))
                .build();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
