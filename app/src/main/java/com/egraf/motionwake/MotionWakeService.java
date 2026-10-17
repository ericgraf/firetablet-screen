/*
 * MotionWakeService — background service that watches the camera for motion,
 * sleeps the screen after a configurable idle period, and wakes the screen
 * when motion is detected.
 */
package com.egraf.motionwake;

import android.Manifest;
import android.app.AlarmManager;
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

import java.security.SecureRandom;
import java.util.Locale;

public final class MotionWakeService extends Service implements MotionCameraManager.MotionListener {

    public static final String TAG = "MotionWakeService";
    public static final String CHANNEL_ID = "motionwatch";
    public static final String PREFS_NAME = "motionwatch";
    public static final String PREF_IDLE_MINUTES = "idle_minutes";
    public static final String PREF_THRESHOLD = "threshold";
    public static final String PREF_CHECK_SECONDS = "check_seconds";
    public static final String PREF_AUTO_START = "auto_start";
    public static final String PREF_CONFIRM_CYCLES = "confirm_cycles";
    public static final String PREF_API_TOKEN = "api_token";
    public static final String PREF_WATCHDOG_ARMED = "watchdog_armed";
    public static final String PREF_HEARTBEAT_TS = "heartbeat_ts";
    public static final String PREF_OTA_ATTEMPT_TS = "ota_attempt_ts";
    public static final String PREF_OTA_APPLIED_TS = "ota_applied_ts";
    public static final String PREF_OTA_LAST_RESULT = "ota_last_result";

    public static final float DEFAULT_THRESHOLD = 3f;
    public static final float MIN_THRESHOLD = 0.5f;
    public static final float MAX_THRESHOLD = 15f;
    public static final long DEFAULT_IDLE_MINUTES = 10;
    public static final long DEFAULT_CHECK_SECONDS = 4L;
    public static final long MIN_CHECK_SECONDS = 2L;
    public static final long MAX_CHECK_SECONDS = 30L;
    public static final int DEFAULT_CONFIRM_CYCLES = 2;
    public static final int MIN_CONFIRM_CYCLES = 1;
    public static final int MAX_CONFIRM_CYCLES = 5;
    /** Watchdog revives the service if its heartbeat is older than this. */
    public static final long WATCHDOG_STALE_MS = 90L * 1000L;
    /** Watchdog alarm period (inexact). */
    public static final long WATCHDOG_PERIOD_MS = 30L * 1000L;

    public static final String ACTION_START = "com.egraf.motionwake.action.START_SERVICE";
    public static final String ACTION_STOP = "com.egraf.motionwake.action.STOP_SERVICE";
    public static final String ACTION_WAKE = "com.egraf.motionwake.action.WAKE";
    public static final String ACTION_SLEEP = "com.egraf.motionwake.action.SLEEP";
    public static final String ACTION_WATCHDOG = "com.egraf.motionwake.action.WATCHDOG";

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
    private volatile long mIdleMs = DEFAULT_IDLE_MINUTES * 60 * 1000L;
    private ApiServer mApi;
    private volatile boolean mForegroundStarted;

    private long mLastHeartbeat = 0L;

    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sRunning) {
                return;
            }
            tickOnce();
            if (mPrefs != null) {
                long now = SystemClock.elapsedRealtime();
                if (now - mLastHeartbeat >= 5000L) {
                    mLastHeartbeat = now;
                    mPrefs.edit().putLong(PREF_HEARTBEAT_TS, now).apply();
                }
            }
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
        ScreenController.ensureWakefulnessTracking(this);
        mPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = mPrefs.edit();
        if (!mPrefs.contains(PREF_API_TOKEN)) {
            editor.putString(PREF_API_TOKEN, generateApiToken());
        }
        if (!mPrefs.contains(PREF_IDLE_MINUTES)) {
            editor.putLong(PREF_IDLE_MINUTES, DEFAULT_IDLE_MINUTES);
        }
        if (!mPrefs.contains(PREF_THRESHOLD)) {
            editor.putFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD);
        }
        if (!mPrefs.contains(PREF_CHECK_SECONDS)) {
            editor.putLong(PREF_CHECK_SECONDS, DEFAULT_CHECK_SECONDS);
        }
        if (!mPrefs.contains(PREF_AUTO_START)) {
            editor.putBoolean(PREF_AUTO_START, true);
        }
        if (!mPrefs.contains(PREF_CONFIRM_CYCLES)) {
            editor.putInt(PREF_CONFIRM_CYCLES, DEFAULT_CONFIRM_CYCLES);
        }
        editor.commit();

        // Local HTTP API (Home Assistant / curl). Stays up while the service
        // lives, whether or not the camera monitor is running.
        mApi = new ApiServer(this);
        mApi.start();

        // Kiosk: keep the system keyguard off while we're device admin.
        // On this Fire build the keyguard reappears after every screen-off
        // timeout; while it's up it pins the display's screen-off timeout to
        // 10 s (its window's userActivityTimeout) and leaves our activity
        // stopped behind it — a state the camera policy rejects.
        ensureKeyguardDisabled();

        // If we were just self-updated (pm path), collect the install result
        // written by the detached child for /update/status.
        collectOtaResult();
    }

    private void ensureKeyguardDisabled() {
        try {
            android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager)
                            getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName admin =
                    new android.content.ComponentName(this, MwDeviceAdmin.class);
            if (dpm != null && dpm.isAdminActive(admin)) {
                // Idempotent; cheap enough to run on every service start
                // (covers the "admin was (re)set" and boot cases).
                boolean ok = dpm.setKeyguardDisabled(admin, true);
                Log.i(TAG, "setKeyguardDisabled -> " + ok);
            }
        } catch (Exception e) {
            Log.w(TAG, "keyguard disable failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // One-shot override for testing the idle pipeline:
        //   am start-foreground-service -n com.egraf.motionwake/.MotionWakeService \
        //       --el idle_minutes_override 1
        if (intent != null && intent.hasExtra("idle_minutes_override")
                && !ACTION_STOP.equals(action)) {
            long minutes = intent.getLongExtra("idle_minutes_override", DEFAULT_IDLE_MINUTES);
            mPrefs.edit().putLong(PREF_IDLE_MINUTES, minutes).apply();
            mIdleMs = idleMsFromPrefs(mPrefs);
        }

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "STOP action — shutting down");
            stopMonitoring();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_WAKE.equals(action)) {
            touchMotion();
            // Wake, then dismiss the keyguard with the real-swipe fix — on a
            // background thread: the combined budget doesn't fit the
            // main thread's ANR allowance.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.util.concurrent.Future<?> f =
                            new ScreenController(MotionWakeService.this).wakeFuture();
                    try {
                        f.get(15000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {
                        // fall through: the swipe gate is a no-op if the
                        // keyguard isn't up anyway
                    }
                    new ScreenController(MotionWakeService.this).dismissKeyguardSwipe();
                }
            }, "motionwatch-wake").start();
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
        if (mApi != null) {
            mApi.stop();
            mApi = null;
        }
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
            if (mCamera != null) {
                return; // already running
            }
            // Start the idle clock now — the timeout counts from when monitoring
            // begins (motion detection hasn't seen anything yet in this process).
            sLastMotionAt = SystemClock.elapsedRealtime();
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
                        } else if (PREF_CHECK_SECONDS.equals(key)) {
                            if (mCamera != null) {
                                mCamera.setIntervalMs(checkSecondsFromPrefs(prefs) * 1000L);
                            }
                        } else if (PREF_CONFIRM_CYCLES.equals(key)) {
                            if (mCamera != null) {
                                mCamera.setConfirmCycles(confirmCyclesFromPrefs(prefs));
                            }
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
            mCamera.setIntervalMs(checkSecondsFromPrefs(mPrefs) * 1000L);
            mCamera.setConfirmCycles(confirmCyclesFromPrefs(mPrefs));
            mCamera.start();
        }
        if (mTick == null) {
            mTick = new Handler(Looper.getMainLooper());
        }
        mTick.post(mTickRunnable);
        sRunning = true;
        armWatchdog();
        Log.i(TAG, "monitor started (idle=" + (mIdleMs / 60000) + "min, threshold="
                + mCamera.getThreshold() + ", check=" + mCamera.getIntervalMs() / 1000 + "s"
                + ", confirm=" + mCamera.getConfirmCycles() + ")");
    }

    private void stopMonitoring() {
        synchronized (mStateLock) {
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
        disarmWatchdog();
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

    static long checkSecondsFromPrefs(SharedPreferences prefs) {
        long seconds = prefs.getLong(PREF_CHECK_SECONDS, DEFAULT_CHECK_SECONDS);
        seconds = Math.max(MIN_CHECK_SECONDS, Math.min(seconds, MAX_CHECK_SECONDS));
        return seconds;
    }

    static int confirmCyclesFromPrefs(SharedPreferences prefs) {
        int n = prefs.getInt(PREF_CONFIRM_CYCLES, DEFAULT_CONFIRM_CYCLES);
        return Math.max(MIN_CONFIRM_CYCLES, Math.min(MAX_CONFIRM_CYCLES, n));
    }

    // --- Watchdog: self-revive if the process dies while monitoring -------
    //
    // A repeating inexact AlarmManager alarm fires every WATCHDOG_PERIOD_MS.
    // While monitoring, the service stamps PREF_HEARTBEAT_TS each tick. The
    // WatchdogReceiver (manifest-registered) wakes the process, reads the
    // armpref + heartbeat from disk, and revives the service if it's stale —
    // which catches crashes and low-memory kills. If the user stops
    // monitoring we disarm (clear the armpref) so the alarm is a no-op.

    private void armWatchdog() {
        if (mPrefs == null) {
            return;
        }
        mPrefs.edit()
                .putBoolean(PREF_WATCHDOG_ARMED, true)
                .putLong(PREF_HEARTBEAT_TS, SystemClock.elapsedRealtime())
                .commit();
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = watchdogPendingIntent();
            if (am != null) {
                am.cancel(pi);
                am.setRepeating(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + WATCHDOG_PERIOD_MS,
                        WATCHDOG_PERIOD_MS, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "watchdog arm failed", e);
        }
    }

    private void disarmWatchdog() {
        if (mPrefs == null) {
            return;
        }
        try {
            mPrefs.edit().putBoolean(PREF_WATCHDOG_ARMED, false).commit();
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(watchdogPendingIntent());
            }
        } catch (Exception e) {
            Log.w(TAG, "watchdog disarm failed", e);
        }
    }

    private PendingIntent watchdogPendingIntent() {
        Intent in = new Intent(ACTION_WATCHDOG).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, 0x2701, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void tickOnce() {
        long idle = idleNowMs();
        sIdleMs = idle;
        if (idle >= mIdleMs && ScreenController.isAwake()) {
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
        if (!ScreenController.isAwake()) {
            Log.i(TAG, "motion while screen off — waking display");
            java.util.concurrent.Future<?> f =
                    new ScreenController(this).wakeFuture();
            try {
                f.get(8000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
            openDashboard(true);
        }
    }

    // ------------------------------------------------------------ HTTP API

    /** Current state + settings (ApiServer GET /status, /status). */
    public String statusJson() {
        long lastAge = 0L;
        long wall = sLastMotionWall;
        if (wall > 0) {
            lastAge = Math.max(0L, System.currentTimeMillis() - wall);
        }
        return "{"
                + "\"ok\":true,"
                + "\"app\":\"motionwake\","
                + "\"running\":" + sRunning + ","
                + "\"screen_on\":" + isInteractive() + ","
                + "\"screen_state\":\"" + ScreenController.screenStateName() + "\","
                + "\"idle_ms\":" + sIdleMs + ","
                + "\"idle_limit_ms\":" + mIdleMs + ","
                + "\"last_motion_age_ms\":" + lastAge + ","
                + "\"idle_minutes\":" + idleMinutesFromPrefs(mPrefs) + ","
                + "\"threshold\":" + jsonNumber(
                        mPrefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD)) + ","
                + "\"check_seconds\":" + checkSecondsFromPrefs(mPrefs) + ","
                + "\"port\":" + ApiServer.DEFAULT_PORT + ","
                + "\"version\":\"" + versionNameJson() + "\","
                // LAN ADB transport (over-the-air OTA channel). Empty after a
                // reboot until `adb tcpip 5555` is re-run once over USB —
                // monitoring (HA/cron) can use this to notice + re-enable.
                + "\"adb_tcp\":\"" + escapeJson(adbTcpProp()) + "\""
                + "}";
    }

    /** service.adb.tcp.port via hidden API (read-only; "" if unset). */
    private static String adbTcpProp() {
        try {
            java.lang.reflect.Method m = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class);
            Object v = m.invoke(null, "service.adb.tcp.port");
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String versionNameJson() {
        try {
            String name = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            return (name == null || name.length() == 0) ? "1.0" : name;
        } catch (Exception e) {
            return "1.0";
        }
    }

    /** Current settings only (ApiServer GET /settings, POST /settings echo). */
    public String settingsJson() {
        return "{"
                + "\"ok\":true,"
                + "\"idle_minutes\":" + idleMinutesFromPrefs(mPrefs) + ","
                + "\"threshold\":" + jsonNumber(
                        mPrefs.getFloat(PREF_THRESHOLD, DEFAULT_THRESHOLD)) + ","
                + "\"check_seconds\":" + checkSecondsFromPrefs(mPrefs) + ","
                + "\"confirm_cycles\":" + confirmCyclesFromPrefs(mPrefs) + ","
                + "\"auto_start\":" + mPrefs.getBoolean(PREF_AUTO_START, true)
                + "}";
    }

    private long idleMinutesFromPrefs(SharedPreferences prefs) {
        long minutes = prefs.getLong(PREF_IDLE_MINUTES, DEFAULT_IDLE_MINUTES);
        return Math.max(1L, Math.min(minutes, 1440L));
    }

    private static String jsonNumber(double v) {
        if (Double.isFinite(v) && v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    public String apiStart() {
        ensureForeground();
        startMonitoring();
        return statusJson();
    }

    public String apiStop() {
        stopMonitoring();
        return statusJson();
    }

    /** Toggle — rest_switch compatibility (HA's rest platform posts to the same URL). */
    public String apiToggle() {
        if (sRunning) {
            stopMonitoring();
        } else {
            ensureForeground();
            startMonitoring();
        }
        return statusJson();
    }

    public String apiShutdown() {
        stopMonitoring();
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        }, "motionwatch-shutdown").start();
        return "{\"ok\":true,\"running\":false}";
    }

    public String apiWake() {
        touchMotion();
        boolean wasOn = ScreenController.isAwake();
        java.util.concurrent.Future<?> f = new ScreenController(this).wakeFuture();
        try {
            f.get(15000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        if (!wasOn) {
            openDashboard(true);
        }
        return statusJson();
    }

    public String apiSleep() {
        // The WRITE_SETTINGS timeout path runs through the Fire nap + ~60s
        // screensaver pipeline; bound the wait a little beyond its 150s budget.
        java.util.concurrent.Future<?> f = new ScreenController(this).sleepFuture();
        try {
            f.get(160000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
        return statusJson();
    }

    // ------------------------------------------------------------ OTA update

    private static final long OTA_MIN_BYTES = 64L * 1024L;

    /**
     * Self-update over HTTP (ApiServer POST /update).
     *
     * Why /data/local/tmp is the channel that works on these Fire builds:
     * the framework registers NO `package_installer` service (checked all
     * 230 registered services), so the Java PackageInstaller API is gone;
     * the IPackageInstaller client interface is stripped to just
     * `uninstall`; and the pm shell session commands each hit a wall for
     * an app uid (reverse/stdin write needs SHELL/ROOT/SYSTEM; the
     * PATH write makes the SERVER open the file, and its file-context
     * allow-list rejects our own `app_data_file` and `media_rw_data_file`
     * — it explicitly endorses `/data/local/tmp`, i.e. `shell_data_file`).
     *
     * So the primary channel is: stage the APK bytes into a pre-created
     * world-writable file at /data/local/tmp (bootstrapped once by
     * deploy.sh, see localTmpPmInstall), then run `pm install -r` on it
     * as OUR OWN uid. The server reads shell_data_file fine, -r keeps our
     * data, and the same signing key is retained (release over release).
     *
     * The command runs as a DETACHED sh child so it survives us being
     * killed mid-replace, and it records rc + stdout/stderr in files
     * under our private dir; the next process instance (relaunched by the
     * MY_PACKAGE_REPLACED broadcast via BootReceiver/BootStarter) reads
     * them in collectOtaResult() and stamps /update/status.
     *
     * Fallback channels for builds without the /data/local/tmp quirk:
     * the standard PackageInstaller double-commit (UpdateInstaller
     * activity + UpdateDoneReceiver), the pm session commands, and a
     * multi-location `pm install -r <path>` sweep.
     *
     * Requires the SAME signing key as the installed build (release over
     * release in our setup); a signature change needs an uninstall first.
     */
    public String apiUpdate(byte[] apk) {
        if (apk == null || apk.length < OTA_MIN_BYTES) {
            return "{\"ok\":false,\"error\":\"body too small to be an APK ("
                    + (apk == null ? 0 : apk.length) + " bytes)\"}";
        }
        if ((apk[0] & 0xFF) != 0x50 || (apk[1] & 0xFF) != 0x4B) {
            return "{\"ok\":false,\"error\":\"not a ZIP/APK (missing PK header)\"}";
        }
        // 1) Stage the APK where pm (running as our own uid) can read it.
        final java.io.File dir = getFilesDir();
        final java.io.File apkFile = new java.io.File(dir, "self-update.apk");
        final java.io.File outLog = new java.io.File(dir, "update.out");
        final java.io.File rcFile = new java.io.File(dir, "update.rc");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(apkFile);
            try {
                fos.write(apk);
                fos.flush();
            } finally {
                fos.close();
            }
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"staging apk file failed: \""
                    + escapeJson(String.valueOf(e)) + "\"}";
        }
        outLog.delete();
        rcFile.delete();
        mPrefs.edit()
                .putLong(PREF_OTA_ATTEMPT_TS, System.currentTimeMillis())
                .putLong(PREF_OTA_APPLIED_TS, 0L)
                .remove(PREF_OTA_LAST_RESULT)
                .apply();
        // 2) PRIMARY: pm install -r of a file under /data/local/tmp
        //    (shell_data_file — the one context the server's file-context
        //    allow-list accepts). See localTmpPmInstall for why this wins.
        String viaLocalTmp = localTmpPmInstall(apk, outLog, rcFile);
        if (isOtaOk(viaLocalTmp)) {
            return viaLocalTmp;
        }
        // 3) Standard PackageInstaller channel, when the service is
        //    registered at runtime (null on these builds).
        String pis = tryPackageInstallerUpdate(apk);
        if (pis != null) {
            return pis;
        }
        // 4) Session-based pm install (create/write/commit).
        String viaSession = sessionPmInstall(apk, apkFile, outLog, rcFile);
        if (isOtaOk(viaSession)) {
            return viaSession;
        }
        // 5) Fallback: classic `pm install -r <path>` multi-location sweep.
        String viaPm = pmInstallMultiStage(apk, apkFile, outLog, rcFile);
        if (isOtaOk(viaPm)) {
            return viaPm;
        }
        StringBuilder all = new StringBuilder();
        all.append("localtmp: ").append(otaErrText(viaLocalTmp)).append(" | ");
        all.append("session-pm: ").append(otaErrText(viaSession)).append(" | ");
        all.append("path-pm: ").append(otaErrText(viaPm));
        String failMsg = all.toString();
        mPrefs.edit().putString(PREF_OTA_LAST_RESULT, failMsg).commit();
        return "{\"ok\":false,\"error\":\"" + escapeJson(failMsg) + "\"}";
    }

    /**
     * PRIMARY install channel: `pm install -r` of a file staged under
     * /data/local/tmp.
     *
     * The server (system_server) opens the apk path itself when running a
     * pm install, and its file-context broker accepts only certain
     * contexts — /data/local/tmp (shell_data_file) is the one it
     * endorses. Our own app_data_file and media_rw_data_file are rejected.
     *
     * The target file is pre-created once by deploy.sh as world-writable
     * (666) at /data/local/tmp/mw-ota.apk. The app (uid u0_aNNN, "other"
     * on the shell:shell 0775 dir) can't CREATE a file there, but it CAN
     * truncate+write an EXISTING 666 file — and that's all we need.
     *
     * Runs as a DETACHED sh child (survives us being replaced); we wait
     * for its rc.
     */
    private String localTmpPmInstall(byte[] apk,
                                     java.io.File outLog, java.io.File rcFile) {
        final java.io.File dest = new java.io.File("/data/local/tmp", "mw-ota.apk");
        // Stage the bytes into the pre-created shared file (truncate+write).
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest, false);
            try {
                fos.write(apk);
                fos.flush();
            } finally {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localTmpPmInstall: stage to " + dest + " failed: " + e);
            return "{\"ok\":false,\"error\":\"localtmp stage failed: \""
                    + escapeJson(String.valueOf(e)) + "\"}";
        }
        outLog.delete();
        rcFile.delete();
        final long t0 = System.currentTimeMillis();
        final String q = "'";
        final String cmd = "/system/bin/pm install -r " + q + dest.getAbsolutePath() + q
                + " > " + q + outLog.getAbsolutePath() + q + " 2>&1"
                + "; echo $? > " + q + rcFile.getAbsolutePath() + q;
        try {
            java.lang.Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"localtmp launch failed: \""
                    + escapeJson(String.valueOf(e)) + "\"}";
        }
        String rc = null;
        long deadline = System.currentTimeMillis() + 30000L;
        while (System.currentTimeMillis() < deadline) {
            if (rcFile.exists()) {
                rc = readFileTrim(rcFile);
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ie) {
                break;
            }
        }
        if (rc == null) {
            return "{\"ok\":false,\"error\":\"localtmp: timeout waiting for rc\"}";
        }
        String head = headOf(outLog, 400);
        if ("0".equals(rc.trim())) {
            Log.i(TAG, "OTA: pm install succeeded from " + dest
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
            mPrefs.edit().putString(PREF_OTA_LAST_RESULT, "pm ok from " + dest).apply();
            // Clean up the shared staging file (best effort).
            try {
                dest.delete();
            } catch (Exception ignored) {
            }
            return "{\"ok\":true,\"state\":\"installing\",\"via\":\"localtmp\","
                    + "\"bytes\":" + apk.length + "}";
        }
        return "{\"ok\":false,\"error\":\"localtmp pm rc=" + rc + " ["
                + head.replace("\n", " ").trim() + "]\"}";
    }

    private static boolean isOtaOk(String json) {
        return json != null && json.indexOf("\"ok\":true") >= 0;
    }

    private static String otaErrText(String json) {
        if (json == null) {
            return "no response";
        }
        int i = json.indexOf("\"error\":\"");
        if (i < 0) {
            return json;
        }
        i += "\"error\":\"".length();
        int j = json.indexOf('"', i);
        return j > 0 ? json.substring(i, j) : json.substring(i);
    }

    /**
     * Session-based self-install via the pm shell session commands
     * (available on these Fire builds; `pm help`):
     *
     *   pm install-create -r -S <bytes>        → "… install session [ID]"
     *   pm install-write -S <bytes> ID base.apk -   (reads APK from STDIN)
     *   pm install-commit ID
     *
     * Staged file is only READ locally (by the sh child, which runs as
     * our uid — app_data_file is fine there); the server streams it in
     * from the pipe, so its file-context allow-list never applies.
     *
     * Runs as a DETACHED sh child (survives our process being replaced);
     * we wait for its rc. Result is still picked up by
     * collectOtaResult()/UpdateDoneReceiver for /update/status.
     */
    private String sessionPmInstall(byte[] apk, java.io.File staged,
                                    java.io.File outLog, java.io.File rcFile) {
        try {
            final long size = apk.length;
            String pkg = getPackageName();
            String q = "'";
            String stagedPath = staged.getAbsolutePath();
            String body = ""
                    + "set +e\n"
                    + "create_out=$(/system/bin/pm install-create -r -S " + size
                    + " --user 0 -i " + pkg + " 2>&1)\n"
                    + "echo \"[install-create] $create_out\"\n"
                    + "sid=$(printf '%s\\n' \"$create_out\" | sed -n 's/.*session \\[\\([0-9]*\\)\\].*/\\1/p')\n"
                    + "if [ -z \"$sid\" ]; then echo no-session-id; exit 1; fi\n"
                    + "echo using-session $sid\n"
                    + "if ! /system/bin/pm install-write -S " + size + " \"$sid\" base.apk "
                    + q + stagedPath + q + "; then\n"
                    + "  /system/bin/pm install-abandon \"$sid\" 2>/dev/null\n"
                    + "  echo install-write-failed\n"
                    + "  exit 2\n"
                    + "fi\n"
                    + "if ! /system/bin/pm install-commit \"$sid\"; then\n"
                    + "  echo install-commit-failed\n"
                    + "  exit 3\n"
                    + "fi\n"
                    + "echo ota-session-committed\n";
            outLog.delete();
            rcFile.delete();
            // `( ... )` SUBSHELL so `exit N` inside leaves the outer
            // `rc=$?`/`echo $rc > file` intact.
            String script = "(\n" + body + "\n) > " + q + outLog.getAbsolutePath() + q
                    + " 2>&1\n"
                    + "rc=$?\n"
                    + "echo $rc > " + q + rcFile.getAbsolutePath() + q + "\n";
            java.lang.Runtime.getRuntime()
                    .exec(new String[]{"sh", "-c", script});
            long deadline = System.currentTimeMillis() + 30000L;
            String r = null;
            while (System.currentTimeMillis() < deadline) {
                if (rcFile.exists()) {
                    r = readFileTrim(rcFile);
                    break;
                }
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            if (r == null) {
                return "{\"ok\":false,\"error\":\"ota session: timeout waiting for rc\"}";
            }
            String head = headOf(outLog, 500);
            if ("0".equals(r.trim())) {
                Log.i(TAG, "OTA: install session committed "
                        + "(pm install-create/write/commit, " + size + " bytes)");
                return "{\"ok\":true,\"state\":\"installing\",\"via\":\"session-pm\","
                        + "\"bytes\":" + size + "}";
            }
            return "{\"ok\":false,\"error\":\"ota session rc=" + r + " ["
                    + head.replace("\n", " ").trim() + "]\"}";
        } catch (Exception e) {
            Log.w(TAG, "sessionPmInstall launch failed", e);
            return "{\"ok\":false,\"error\":\"ota session launch failed: \""
                    + escapeJson(String.valueOf(e)) + "\"}";
        }
    }

    /**
     * `pm install` self-install with multi-location staging.
     *
     * Each attempt runs as a DETACHED sh child (it survives us being
     * killed mid-replace); we wait for its rc before trying the next
     * location. Result files (update.rc / update.out, in our filesDir)
     * are read by collectOtaResult() on the next start — and by the
     * /update/status probe (self-healing); UpdateDoneReceiver
     * (INITIALIZATION_COMPLETE) stamps `applied` regardless.
     */
    private String pmInstallMultiStage(byte[] apk, java.io.File staged,
                                       java.io.File outLog, java.io.File rcFile) {
        java.io.File[] locations = new java.io.File[3];
        int n = 0;
        try {
            java.io.File ext = getExternalFilesDir(null);
            if (ext != null) {
                locations[n++] = new java.io.File(ext, "self-update.apk");
            }
        } catch (Throwable ignored) {
        }
        try {
            java.io.File sd = android.os.Environment.getExternalStorageDirectory();
            if (sd != null) {
                locations[n++] = new java.io.File(sd, "self-update.apk");
            }
        } catch (Throwable ignored) {
        }
        locations[n++] = staged; // app filesDir (last: rejected context here)

        StringBuilder failures = new StringBuilder();
        for (int i = 0; i < n; i++) {
            final java.io.File dest = locations[i];
            final String path = dest.getAbsolutePath();
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
                try {
                    fos.write(apk);
                    fos.flush();
                } finally {
                    try {
                        fos.close();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                failures.append("stage ").append(path).append(" failed: ")
                        .append(e).append("; ");
                continue;
            }
            outLog.delete();
            rcFile.delete();
            final long t0 = System.currentTimeMillis();
            try {
                String q = "'";
                String cmd = "/system/bin/pm install -r "
                        + q + path + q
                        + " > " + q + outLog.getAbsolutePath() + q + " 2>&1"
                        + "; echo $? > " + q + rcFile.getAbsolutePath() + q;
                java.lang.Runtime.getRuntime()
                        .exec(new String[]{"sh", "-c", cmd});
            } catch (Exception e) {
                failures.append("launch ").append(path).append(" failed: ")
                        .append(e).append("; ");
                continue;
            }
            // Wait for the child to finish (pm fails fast on a bad file
            // context; success takes a few seconds to replace the pkg).
            String rc = null;
            long deadline = System.currentTimeMillis() + 30000L;
            while (System.currentTimeMillis() < deadline) {
                if (rcFile.exists()) {
                    rc = readFileTrim(rcFile);
                    break;
                }
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            if (rc == null) {
                failures.append("timeout waiting for pm at ").append(path).append("; ");
                continue;
            }
            String head = headOf(outLog, 400);
            if ("0".equals(rc.trim())) {
                Log.i(TAG, "OTA: pm install succeeded from " + path
                        + " in " + (System.currentTimeMillis() - t0) + "ms");
                mPrefs.edit()
                        .putString(PREF_OTA_LAST_RESULT,
                                "pm ok from " + path)
                        .apply();
                return "{\"ok\":true,\"state\":\"installing\",\"via\":\"pm\","
                        + "\"location\":\"" + escapeJson(path) + "\","
                        + "\"bytes\":" + apk.length + "}";
            }
            failures.append("pm ").append(path).append(" rc=").append(rc)
                    .append(" [").append(head.replace("\n", " ").trim())
                    .append("]; ");
        }
        String failMsg = failures.toString().trim();
        mPrefs.edit().putString(PREF_OTA_LAST_RESULT, failMsg).commit();
        return "{\"ok\":false,\"error\":\"pm install failed: \""
                + escapeJson(failMsg) + "\"}";
    }

    /**
     * Standard PackageInstaller self-update (double commit: commit our own
     * UpdateInstaller as the "confirmation" step, it issues the real
     * commit; UpdateDoneReceiver stamps the applied ts). No install UI is
     * ever shown — what an unattended wall tablet needs.
     *
     * Works on these Fire builds because PisBridge fetches the
     * IPackageInstaller binder via
     * ActivityThread.sPackageManager.getPackageInstaller() — the
     * "package_installer" context service is not registered here, but the
     * framework method is (verified via /update/diag).
     *
     * @return a response JSON, or null when the PIS binder is not
     *         available at all (caller falls back to `pm install`).
     */
    private String tryPackageInstallerUpdate(byte[] apk) {
        Object pis = PisBridge.installer();
        if (pis == null) {
            return null;
        }
        try {
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            otaSetAppId(params, getPackageName());
            int sessionId = PisBridge.createSession(pis, params);
            if (sessionId < 0) {
                // The binder exists but the session methods were stripped
                // from the runtime interface (these Fire builds expose only
                // `uninstall`) — let the caller fall back to pm install.
                Log.w(TAG, "PIS session surface unavailable — falling back to pm");
                return null;
            }
            Object session = PisBridge.openSession(pis, sessionId);
            if (session == null) {
                return "{\"ok\":false,\"error\":\"could not open install session "
                        + sessionId + "\"}";
            }
            try {
                PisBridge.writeThrough(session, "motionwake-update.apk", apk);
                PisBridge.sessionSeal(session);
            } catch (Throwable e) {
                try {
                    PisBridge.sessionAbandon(session);
                } catch (Exception ignored) {
                }
                return "{\"ok\":false,\"error\":\"session write failed: "
                        + escapeJson(String.valueOf(e)) + "\"}";
            }
            android.content.Intent installIntent =
                    new android.content.Intent(this, UpdateInstaller.class);
            installIntent.putExtra("sid", sessionId);
            installIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this,
                    UpdateInstaller.STAGE2_REQUEST + 1000,
                    installIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            PisBridge.sessionCommit(session, pi.getIntentSender());
            Log.i(TAG, "OTA: committed update session " + sessionId
                    + " (" + apk.length + " bytes)");
            return "{\"ok\":true,\"state\":\"committing\",\"session\":"
                    + sessionId + ",\"bytes\":" + apk.length + "}";
        } catch (Throwable e) {
            Log.w(TAG, "OTA (PIS path) failed", e);
            return "{\"ok\":false,\"error\":\"" + escapeJson(String.valueOf(e)) + "\"}";
        }
    }

    /**
     * Collect the `pm install` result written by the detached update child
     * (runs on every service start — a no-op when the files are absent).
     * Success stamps the applied ts for /update/status; failure keeps the
     * state "pending" with last_result as the diagnostic.
     */
    public void collectOtaResult() {
        try {
            final java.io.File dir = getFilesDir();
            final java.io.File rcFile = new java.io.File(dir, "update.rc");
            if (!rcFile.exists()) {
                return;
            }
            final String rc = readFileTrim(rcFile);
            // pm's error message (if any) is at the HEAD of the output.
            final String head = headOf(new java.io.File(dir, "update.out"), 800);
            final boolean ok = "0".equals(rc);
            mPrefs.edit()
                    .putString(PREF_OTA_LAST_RESULT,
                            "pm rc=" + rc + (head.isEmpty() ? "" : " | " + head))
                    .commit();
            if (ok) {
                mPrefs.edit()
                        .putLong(PREF_OTA_APPLIED_TS, System.currentTimeMillis())
                        .commit();
            }
            new java.io.File(dir, "update.out").delete();
            new java.io.File(dir, "self-update.apk").delete();
            rcFile.delete();
            // Staging copies on external storage (survive the replace).
            try {
                java.io.File ext = getExternalFilesDir(null);
                if (ext != null) {
                    new java.io.File(ext, "self-update.apk").delete();
                }
                java.io.File sd = android.os.Environment.getExternalStorageDirectory();
                if (sd != null) {
                    new java.io.File(sd, "self-update.apk").delete();
                }
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "OTA result collected: rc=" + rc + " ok=" + ok
                    + " head=[" + head.trim() + "]");
        } catch (Exception e) {
            Log.w(TAG, "collectOtaResult failed", e);
        }
    }

    private static String headOf(java.io.File f, int maxChars) {
        try {
            java.io.FileInputStream is = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0 && bos.size() < maxChars * 2) {
                bos.write(buf, 0, n);
            }
            is.close();
            String s = new String(bos.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8).replace('\n', '|').trim();
            return s.length() > maxChars ? s.substring(0, maxChars) : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readFileTrim(java.io.File f) {
        try {
            java.io.FileInputStream is = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() >= 4096) {
                    break;
                }
            }
            is.close();
            return new String(bos.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Diagnostic: reflect the install-related API surface available at
     * runtime, so we can pick a working self-install channel without
     * guessing. GET /update/diag.
     */
    public String diagJson() {
        StringBuilder out = new StringBuilder("{\"ok\":true");
        // 1) Amazon's own package manager binder.
        Object amz = null;
        try {
            amz = (Object) getSystemService("amazonpackagemanager");
        } catch (Exception ignored) {
        }
        if (amz != null && amz.getClass().getInterfaces().length > 0) {
            out.append(",\"amz\":[");
            java.lang.reflect.Method[] ms = amz.getClass().getInterfaces()[0].getMethods();
            boolean first = true;
            for (java.lang.reflect.Method m : ms) {
                String name = m.getName();
                if (!name.contains("install") && !name.contains("Install")
                        && !name.contains("update") && !name.contains("Update")) {
                    continue;
                }
                StringBuilder sig = new StringBuilder(name).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) {
                        sig.append(",");
                    }
                    sig.append(ps[i].getName());
                }
                sig.append("):").append(m.getReturnType().getName());
                if (!first) {
                    out.append(",");
                }
                first = false;
                out.append('"').append(escapeJson(sig.toString())).append('"');
            }
            out.append("]");
        } else {
            out.append(",\"amz\":null");
        }
        // 2) Framework IPackageManager (via ActivityThread static field).
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object pmProxy = null;
            String fieldName = "";
            for (java.lang.reflect.Field f : at.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!"android.content.pm.IPackageManager".equals(f.getType().getName())) {
                    continue;
                }
                f.setAccessible(true);
                pmProxy = f.get(null);
                fieldName = f.getName();
                break;
            }
            if (pmProxy != null && pmProxy.getClass().getInterfaces().length > 0) {
                out.append(",\"pm_field\":\"").append(escapeJson(fieldName)).append("\",\"pm\":[");
                boolean first = true;
                for (java.lang.reflect.Method m : pmProxy.getClass().getInterfaces()[0].getMethods()) {
                    String name = m.getName();
                    if (name.indexOf("nstall") < 0) {
                        continue;
                    }
                    StringBuilder sig = new StringBuilder(name).append("(");
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) {
                            sig.append(",");
                        }
                        sig.append(ps[i].getName());
                    }
                    sig.append("):").append(m.getReturnType().getName());
                    if (!first) {
                        out.append(",");
                    }
                    first = false;
                    out.append('"').append(escapeJson(sig.toString())).append('"');
                }
                out.append("]");
            } else {
                out.append(",\"pm\":null");
            }
        } catch (Throwable e) {
            out.append(",\"pm\":\"err:").append(escapeJson(String.valueOf(e))).append('"');
        }
        // 3) The IPackageInstaller binder itself (via PisBridge).
        Object pis = PisBridge.installer();
        if (pis != null) {
            out.append(",\"pis_class\":\"").append(escapeJson(pis.getClass().getName())).append("\"");
            out.append(",\"pis\":[");
            boolean first = true;
            for (java.lang.reflect.Method m : pis.getClass().getMethods()) {
                if (first) {
                    first = false;
                } else {
                    out.append(",");
                }
                String name = m.getName();
                if (out.length() > 2500) {
                    break;
                }
                out.append('"').append(escapeJson(name)).append('"');
            }
            out.append("]");
        } else {
            out.append(",\"pis\":null");
        }
        // 4) OTA staging state (for debugging pm install failures).
        try {
            java.io.File st = new java.io.File(getFilesDir(), "self-update.apk");
            java.io.File uo = new java.io.File(getFilesDir(), "update.out");
            java.io.File ur = new java.io.File(getFilesDir(), "update.rc");
            out.append(",\"staged\":{\"exists\":").append(st.exists())
                    .append(",\"len\":").append(st.length()).append("}");
            out.append(",\"rc_file\":").append(ur.exists()
                    ? "\"" + escapeJson(readFileTrim(ur)) + "\"" : "false");
            if (uo.exists()) {
                out.append(",\"update_out\":\"").append(escapeJson(headOf(uo, 700))).append("\"");
            }
            String exts = null;
            try {
                java.io.File e = getExternalFilesDir(null);
                exts = e == null ? "null" : e.getAbsolutePath();
            } catch (Throwable t) {
                exts = "err:" + t;
            }
            String sds = null;
            try {
                java.io.File s = android.os.Environment.getExternalStorageDirectory();
                sds = s == null ? "null" : s.getAbsolutePath();
            } catch (Throwable t) {
                sds = "err:" + t;
            }
            out.append(",\"ext_dir\":\"").append(escapeJson(String.valueOf(exts))).append("\"");
            out.append(",\"sd_dir\":\"").append(escapeJson(String.valueOf(sds))).append("\"");
        } catch (Throwable t) {
            out.append(",\"staging\":\"err:").append(escapeJson(String.valueOf(t))).append("\"");
        }
        out.append("}");
        return out.toString();
    }

    /** GET /update/status — current version + last OTA attempt/result. */
    public String updateStatusJson() {
        // Self-healing probe: the detached pm child can finish (and write
        // its result files) just after our post-replace start — collect on
        // every check, not only in onCreate (no-op when nothing pending).
        collectOtaResult();
        String version = "0";
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            version = String.valueOf(info.versionCode);
        } catch (Exception ignored) {
        }
        long attempt = mPrefs.getLong(PREF_OTA_ATTEMPT_TS, 0L);
        long applied = mPrefs.getLong(PREF_OTA_APPLIED_TS, 0L);
        String state = attempt == 0L
                ? "none"
                : (applied > attempt) ? "applied" : "pending";
        String lastResult = mPrefs.getString(PREF_OTA_LAST_RESULT, "");
        return "{\"ok\":true,\"version\":" + version + ",\"state\":\"" + state
                + "\",\"attempt_ts\":" + attempt + ",\"applied_ts\":" + applied
                + ",\"last_result\":\"" + escapeJson(lastResult) + "\"}";
    }

    /** Open the session's APK file for writing, handling the return-type
     *  variance (OutputStream on this jar, ParcelFileDescriptor on older
     *  runtimes) by reflection. */
    private static java.io.OutputStream otaOpenWrite(
            android.content.pm.PackageInstaller.Session session, String name)
            throws Exception {
        java.lang.reflect.Method m = null;
        for (java.lang.reflect.Method x
                : android.content.pm.PackageInstaller.Session.class.getMethods()) {
            if (x.getName().equals("openWrite") && x.getParameterTypes().length == 3) {
                m = x;
                break;
            }
        }
        if (m == null) {
            throw new IllegalStateException("Session.openWrite not found");
        }
        Object w;
        try {
            w = m.invoke(session, name, 0L, -1L);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable c = ite.getCause() != null ? ite.getCause() : ite;
            throw new Exception("openWrite failed: " + c, c);
        }
        if (w instanceof java.io.OutputStream) {
            return (java.io.OutputStream) w;
        }
        android.os.ParcelFileDescriptor pfd = (android.os.ParcelFileDescriptor) w;
        return new java.io.FileOutputStream(pfd.getFileDescriptor());
    }

    /** Attach the creating-app id (jar: setAppPackageName, newer runtimes:
     *  setAppId) — reflect whichever exists. */
    private static void otaSetAppId(android.content.pm.PackageInstaller.SessionParams params,
            String pkg) {
        for (String m : new String[]{"setAppPackageName", "setAppId"}) {
            try {
                params.getClass().getMethod(m, String.class).invoke(params, pkg);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    /** Create the install session (jar: createSession; older semantics:
     *  openSession(SessionParams)) — reflect whichever exists. */
    private static int otaCreateSession(android.content.pm.PackageInstaller installer,
            android.content.pm.PackageInstaller.SessionParams params) throws Exception {
        for (String m : new String[]{"createSession", "openSession"}) {
            try {
                Object r = installer.getClass().getMethod(m,
                        android.content.pm.PackageInstaller.SessionParams.class)
                        .invoke(installer, params);
                return ((Number) r).intValue();
            } catch (java.lang.NoSuchMethodException ignored) {
            }
        }
        throw new IllegalStateException("createSession/openSession not found");
    }

    /** Seal the session (fseal() on the device runtime, the deprecated
     *  fsync(os) variant on the vendored jar) — reflect whichever exists.
     *  The stream has already been closed by the caller. */
    private static void otaSeal(android.content.pm.PackageInstaller.Session session)
            throws Exception {
        try {
            session.getClass().getMethod("fseal").invoke(session);
            return;
        } catch (java.lang.NoSuchMethodException ignored) {
            // older/newer variant
        }
        try {
            session.getClass().getMethod("fsync", java.io.OutputStream.class)
                    .invoke(session, (Object) null);
        } catch (java.lang.NoSuchMethodException nse) {
            throw new IllegalStateException("Session.fseal/fsync not found");
        }
    }

    /** Called by UpdateDoneReceiver after our process has been replaced. */
    public static void markOtaApplied(android.content.Context app, long ts) {
        try {
            app.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putLong(PREF_OTA_APPLIED_TS, ts)
                    .commit();
        } catch (Exception ignored) {
        }
    }

    private static String escapeJson(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\');
                b.append(c);
            } else if (c < 0x20) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Accepts a flat JSON body with a subset of
     * {"idle_minutes":n,"threshold":f,"check_seconds":n,"confirm_cycles":n,
     *  "auto_start":bool}; applies the present-and-valid keys.
     * Returns settingsJson() on success.
     */
    public String apiSetSettings(String json) {
        Double idle = numFromJson(json, "idle_minutes");
        Double threshold = numFromJson(json, "threshold");
        Double check = numFromJson(json, "check_seconds");
        Double confirm = numFromJson(json, "confirm_cycles");
        Boolean autoStart = boolFromJson(json, "auto_start");
        if (idle == null && threshold == null && check == null
                && confirm == null && autoStart == null) {
            return "{\"ok\":false,\"error\":\"no recognized keys "
                    + "(idle_minutes, threshold, check_seconds, confirm_cycles, "
                    + "auto_start)\"}";
        }
        boolean badRange = (idle != null && (idle < 1 || idle > 1440))
                || (threshold != null && (threshold < MIN_THRESHOLD || threshold > MAX_THRESHOLD))
                || (check != null && (check < MIN_CHECK_SECONDS || check > MAX_CHECK_SECONDS))
                || (confirm != null && (confirm < MIN_CONFIRM_CYCLES || confirm > MAX_CONFIRM_CYCLES));
        if (badRange) {
            return "{\"ok\":false,\"error\":\"value out of range "
                    + "(idle 1-1440, threshold " + MIN_THRESHOLD + "-" + MAX_THRESHOLD
                    + ", check " + MIN_CHECK_SECONDS + "-" + MAX_CHECK_SECONDS
                    + ", confirm " + MIN_CONFIRM_CYCLES + "-" + MAX_CONFIRM_CYCLES + ")\"}";
        }
        SharedPreferences.Editor editor = mPrefs.edit();
        if (idle != null) {
            editor.putLong(PREF_IDLE_MINUTES, idle.longValue());
        }
        if (threshold != null) {
            editor.putFloat(PREF_THRESHOLD, threshold.floatValue());
        }
        if (check != null) {
            editor.putLong(PREF_CHECK_SECONDS, check.longValue());
        }
        if (confirm != null) {
            editor.putInt(PREF_CONFIRM_CYCLES, confirm.intValue());
        }
        if (autoStart != null) {
            editor.putBoolean(PREF_AUTO_START, autoStart);
        }
        editor.apply();
        return settingsJson();
    }

    // ------------------------------------------------------------ browser

    public static final String PREF_BROWSER_URL = "browser_url";

    /** Current dashboard URL + which browser would open it (ApiServer GET /browser). */
    public String browserJson() {
        String url = mPrefs.getString(PREF_BROWSER_URL, "");
        String pkg = defaultBrowserPackage();
        return "{"
                + "\"ok\":true,"
                + "\"url\":\"" + jsonEscape(url) + "\","
                + "\"browser\":\"" + jsonEscape(pkg == null ? "" : pkg) + "\""
                + "}";
    }

    /**
     * Set the dashboard URL ({"url":"..."}, optional) and open it in the
     * tablet's default browser. Returns the browser state.
     */
    public String apiBrowser(String bodyJson) {
        String url = strFromJson(bodyJson, "url");
        if (url != null && url.length() > 0) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            mPrefs.edit().putString(PREF_BROWSER_URL, url).apply();
        }
        url = mPrefs.getString(PREF_BROWSER_URL, "");
        boolean opened = openInBrowser(url);
        return "{"
                + "\"ok\":" + (url.length() > 0) + ","
                + "\"opened\":" + opened + ","
                + "\"url\":\"" + jsonEscape(url) + "\","
                + "\"browser\":\"" + jsonEscape(defaultBrowserPackage() == null ? ""
                        : defaultBrowserPackage()) + "\""
                + "}";
    }

    /** Re-open the stored dashboard URL in the default browser (reload). */
    public String apiBrowserReload() {
        String url = mPrefs.getString(PREF_BROWSER_URL, "");
        if (url.length() == 0) {
            return "{\"ok\":false,\"error\":\"no dashboard URL stored yet "
                    + "(POST /browser {url})\"}";
        }
        boolean opened = openInBrowser(url);
        return "{"
                + "\"ok\":true,"
                + "\"opened\":" + opened + ","
                + "\"url\":\"" + jsonEscape(url) + "\""
                + "}";
    }

    private String defaultBrowserPackage() {
        try {
            Intent probe = new Intent(Intent.ACTION_VIEW);
            probe.setData(android.net.Uri.parse("https://www.home-assistant.io/"));
            android.content.pm.ResolveInfo ri =
                    getPackageManager().resolveActivity(probe, 0);
            return (ri != null && ri.activityInfo != null) ? ri.activityInfo.packageName : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Open the dashboard in our built-in DashboardBrowser (singleTask
     * instance is reused: relaunch re-loads the page, so wake always shows
     * a fresh dashboard). Returns true if launch was issued.
     */
    public boolean openDashboard(boolean reload) {
        // Wake path: dismiss the system keyguard with a genuine swipe if it
        // came up after the last sleep (on this build only a user swipe
        // sticks — while up it also pins the screen-off timeout to 10 s).
        // Fall back to the legacy requestDismissKeyguard attempt.
        boolean dismissed = new ScreenController(this).dismissKeyguardSwipe();
        if (!dismissed) {
            dismissKeyguard();
        }
        String url = mPrefs.getString(PREF_BROWSER_URL, "");
        if (url == null || url.length() == 0) {
            Log.i(TAG, "openDashboard: no dashboard URL stored — skipped");
            return false;
        }
        try {
            Intent intent = new Intent(this, DashboardBrowser.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(DashboardBrowser.EXTRA_URL, url);
            intent.putExtra(DashboardBrowser.EXTRA_RELOAD, reload);
            startActivity(intent);
            Log.i(TAG, "dashboard open " + url + " reload=" + reload);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "dashboard startActivity failed", e);
        }
        // Fallback: shell am (last resort on this Fire build).
        return execSimple(new String[]{"am", "start", "--user", "0",
                "-n", getPackageName() + "/.DashboardBrowser",
                "--es", "com.egraf.motionwake.extra.URL", url,
                "--ez", "com.egraf.motionwake.extra.RELOAD",
                reload ? "true" : "false"}) == 0;
    }

    /** Fire the transparent keyguard-dismiss activity (no-op if not up). */
    private void dismissKeyguard() {
        try {
            Intent dismiss = new Intent(this, KeyguardDismissing.class);
            dismiss.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(dismiss);
        } catch (Exception e) {
            Log.w(TAG, "keyguard dismiss launch failed", e);
        }
    }

    private boolean openInBrowser(String url) {
        if (url == null || url.length() == 0) {
            return false;
        }
        try {
            Intent intent = new Intent(this, DashboardBrowser.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(DashboardBrowser.EXTRA_URL, url);
            intent.putExtra(DashboardBrowser.EXTRA_RELOAD, true);
            startActivity(intent);
            Log.i(TAG, "browser open (built-in) " + url);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "browser startActivity failed", e);
        }
        // Fallback: shell am (works from the shell uid; silent-fails in app
        // uid on this Fire build, so it's the last resort).
        return execSimple(new String[]{"am", "start", "--user", "0",
                "-a", "android.intent.action.VIEW", "-d", url}) == 0;
    }

    private static int execSimple(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            java.io.InputStream stdout = p.getInputStream();
            byte[] buf = new byte[256];
            while (stdout.read(buf) > 0) {
                // drain
            }
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Minimal flat-JSON string extractor for the API (value stays a bare string). */
    private static String strFromJson(String json, String key) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        i = skipWs(json, i + needle.length());
        int n = json.length();
        if (i >= n || json.charAt(i) != ':') {
            return null;
        }
        i = skipWs(json, i + 1);
        if (i >= n || json.charAt(i) != '"') {
            return null;
        }
        int end = i + 1;
        StringBuilder sb = new StringBuilder();
        while (end < n) {
            char c = json.charAt(end);
            if (c == '\\' && end + 1 < n) {
                char next = json.charAt(end + 1);
                if (next == '"' || next == '\\' || next == 'n' || next == 'r' || next == 't') {
                    sb.append(next == 'n' ? '\n' : next == 'r' ? '\r'
                            : next == 't' ? '\t' : next);
                    end += 2;
                    continue;
                }
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
            end++;
        }
        return null;
    }

    /** Minimal flat-JSON number extractor for the API (values are plain numbers). */
    private static Double numFromJson(String json, String key) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        i += needle.length();
        int n = json.length();
        i = skipWs(json, i);
        if (i >= n || json.charAt(i) != ':') {
            return null;
        }
        i = skipWs(json, i + 1);
        int start = i;
        while (i < n) {
            char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            return null;
        }
        try {
            return Double.valueOf(json.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()
                && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
            i++;
        }
        return i;
    }

    /** Minimal flat-JSON boolean extractor for the API (true/false literals). */
    private static Boolean boolFromJson(String json, String key) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        i += needle.length();
        int n = json.length();
        i = skipWs(json, i);
        if (i >= n || json.charAt(i) != ':') {
            return null;
        }
        i = skipWs(json, i + 1);
        if (json.startsWith("true", i)) {
            return Boolean.TRUE;
        }
        if (json.startsWith("false", i)) {
            return Boolean.FALSE;
        }
        return null;
    }

    // ------------------------------------------------------------ API token

    public String getApiToken() {
        return mPrefs.getString(PREF_API_TOKEN, "");
    }

    public static String generateApiToken() {
        SecureRandom rnd = new SecureRandom();
        byte[] bytes = new byte[16];
        rnd.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
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
