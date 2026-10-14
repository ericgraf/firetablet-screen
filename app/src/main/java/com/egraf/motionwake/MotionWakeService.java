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
    public static final String PREF_API_TOKEN = "api_token";

    public static final float DEFAULT_THRESHOLD = 3f;
    public static final float MIN_THRESHOLD = 0.5f;
    public static final float MAX_THRESHOLD = 15f;
    public static final long DEFAULT_IDLE_MINUTES = 10;
    public static final long DEFAULT_CHECK_SECONDS = 4L;
    public static final long MIN_CHECK_SECONDS = 2L;
    public static final long MAX_CHECK_SECONDS = 30L;

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
    private volatile long mIdleMs = DEFAULT_IDLE_MINUTES * 60 * 1000L;
    private ApiServer mApi;
    private volatile boolean mForegroundStarted;

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
            mCamera.start();
        }
        if (mTick == null) {
            mTick = new Handler(Looper.getMainLooper());
        }
        mTick.post(mTickRunnable);
        sRunning = true;
        Log.i(TAG, "monitor started (idle=" + (mIdleMs / 60000) + "min, threshold="
                + mCamera.getThreshold() + ", check=" + mCamera.getIntervalMs() / 1000 + "s)");
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
                + "\"version\":\"" + versionNameJson() + "\""
                + "}";
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

    /**
     * Accepts a flat JSON body with a subset of
     * {"idle_minutes":n,"threshold":f,"check_seconds":n}; applies the
     * present-and-valid keys. Returns settingsJson() on success.
     */
    public String apiSetSettings(String json) {
        Double idle = numFromJson(json, "idle_minutes");
        Double threshold = numFromJson(json, "threshold");
        Double check = numFromJson(json, "check_seconds");
        if (idle == null && threshold == null && check == null) {
            return "{\"ok\":false,\"error\":\"no recognized keys "
                    + "(idle_minutes, threshold, check_seconds)\"}";
        }
        boolean badRange = (idle != null && (idle < 1 || idle > 1440))
                || (threshold != null && (threshold < MIN_THRESHOLD || threshold > MAX_THRESHOLD))
                || (check != null && (check < MIN_CHECK_SECONDS || check > MAX_CHECK_SECONDS));
        if (badRange) {
            return "{\"ok\":false,\"error\":\"value out of range "
                    + "(idle 1-1440, threshold " + MIN_THRESHOLD + "-" + MAX_THRESHOLD
                    + ", check " + MIN_CHECK_SECONDS + "-" + MAX_CHECK_SECONDS + ")\"}";
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
        // Wake path: if the system keyguard came up after the last sleep,
        // dismiss it (it also pins the screen-off timeout to 10 s while up).
        dismissKeyguard();
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
