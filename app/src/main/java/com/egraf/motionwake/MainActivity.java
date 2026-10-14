/*
 * MainActivity — settings + live status UI (programmatic layout, no appcompat).
 */
package com.egraf.motionwake;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 42;

    private TextView mStatus;
    private EditText mIdleInput;
    private EditText mThresholdInput;
    private EditText mCheckInput;
    private EditText mUrlInput;
    private TextView mApiTokenView;
    private CheckBox mAutoStart;

    private final Handler mPoll = new Handler(Looper.getMainLooper());
    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            mPoll.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestRuntimePermissions();
    }

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        mStatus = new TextView(this);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mStatus.setTypeface(Typeface.MONOSPACE);
        root.addView(mStatus, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(12)));

        root.addView(row(
                button(R.string.button_start, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ControlReceiver.startService(MainActivity.this);
                    }
                }),
                button(R.string.button_stop, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            stopService(new Intent(MainActivity.this, MotionWakeService.class));
                        } catch (Exception ignored) {
                        }
                    }
                })),
                lp(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(8)));

        root.addView(row(
                button(R.string.button_wake, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendAction(MotionWakeService.ACTION_WAKE);
                    }
                }),
                button(R.string.button_sleep, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendAction(MotionWakeService.ACTION_SLEEP);
                    }
                })),
                lp(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(12)));

        mIdleInput = new EditText(this);
        mIdleInput.setHint(R.string.idle_minutes);
        mIdleInput.setSingleLine(true);
        mIdleInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        long idleInitial = prefs().getLong(MotionWakeService.PREF_IDLE_MINUTES,
                MotionWakeService.DEFAULT_IDLE_MINUTES);
        mIdleInput.setText(String.valueOf(idleInitial));
        root.addView(mIdleInput, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(4)));

        mThresholdInput = new EditText(this);
        mThresholdInput.setHint(R.string.threshold);
        mThresholdInput.setSingleLine(true);
        float thInitial = prefs().getFloat(MotionWakeService.PREF_THRESHOLD,
                MotionWakeService.DEFAULT_THRESHOLD);
        mThresholdInput.setText(formatNumber(thInitial));
        root.addView(mThresholdInput, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(4)));

        mCheckInput = new EditText(this);
        mCheckInput.setHint(R.string.check_seconds);
        mCheckInput.setSingleLine(true);
        long checkInitial = prefs().getLong(MotionWakeService.PREF_CHECK_SECONDS,
                MotionWakeService.DEFAULT_CHECK_SECONDS);
        mCheckInput.setText(String.valueOf(checkInitial));
        root.addView(mCheckInput, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(4)));

        mUrlInput = new EditText(this);
        mUrlInput.setHint(R.string.dashboard_url);
        mUrlInput.setSingleLine(true);
        String urlInitial = prefs().getString(MotionWakeService.PREF_BROWSER_URL, "");
        if (urlInitial != null) {
            mUrlInput.setText(urlInitial);
        }
        root.addView(mUrlInput, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(4)));

        Button applyBtn = button(R.string.button_apply, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applySettings();
            }
        });
        root.addView(applyBtn, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(16)));

        TextView apiTitle = new TextView(this);
        apiTitle.setText(R.string.api_title);
        apiTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        apiTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(apiTitle, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(20)));

        TextView apiHint = new TextView(this);
        apiHint.setText(R.string.api_hint);
        apiHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(apiHint, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(2)));

        mApiTokenView = new TextView(this);
        mApiTokenView.setHint(R.string.api_token);
        mApiTokenView.setTypeface(Typeface.MONOSPACE);
        mApiTokenView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mApiTokenView.setText(prefs().getString(MotionWakeService.PREF_API_TOKEN, ""));
        mApiTokenView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTokenToClipboard();
            }
        });
        root.addView(mApiTokenView, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(4)));

        Button newTokenBtn = button(R.string.button_new_token, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = MotionWakeService.generateApiToken();
                prefs().edit().putString(MotionWakeService.PREF_API_TOKEN, t).apply();
                mApiTokenView.setText(t);
                toast(R.string.token_updated);
            }
        });
        root.addView(newTokenBtn, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(4)));

        mAutoStart = new CheckBox(this);
        mAutoStart.setText(R.string.auto_start);
        boolean autoStart = prefs().getBoolean(MotionWakeService.PREF_AUTO_START, true);
        mAutoStart.setChecked(autoStart);
        mAutoStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs().edit()
                        .putBoolean(MotionWakeService.PREF_AUTO_START, mAutoStart.isChecked())
                        .apply();
            }
        });
        root.addView(mAutoStart, lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MotionWakeService.PREFS_NAME, MODE_PRIVATE);
    }

    private void applySettings() {
        boolean ok = true;
        long idle = parseLong(mIdleInput.getText().toString());
        float th = parseFloat(mThresholdInput.getText().toString());
        long check = parseLong(mCheckInput.getText().toString());
        String url = mUrlInput.getText().toString().trim();
        if (idle <= 0 || idle > 1440) {
            toast(R.string.invalid_idle);
            ok = false;
        }
        if (th < MotionWakeService.MIN_THRESHOLD || th > MotionWakeService.MAX_THRESHOLD) {
            toast(R.string.invalid_threshold);
            ok = false;
        }
        if (check < MotionWakeService.MIN_CHECK_SECONDS
                || check > MotionWakeService.MAX_CHECK_SECONDS) {
            toast(R.string.invalid_check);
            ok = false;
        }
        if (url.length() > 0
                && !url.startsWith("http://") && !url.startsWith("https://")) {
            toast(R.string.invalid_url);
            ok = false;
        }
        if (ok) {
            prefs().edit()
                    .putLong(MotionWakeService.PREF_IDLE_MINUTES, idle)
                    .putFloat(MotionWakeService.PREF_THRESHOLD, th)
                    .putLong(MotionWakeService.PREF_CHECK_SECONDS, check)
                    .putString(MotionWakeService.PREF_BROWSER_URL, url)
                    .apply();
            toast(R.string.applied);
        }
    }

    private void sendAction(String action) {
        Intent i = new Intent(action, null, this, ControlReceiver.class);
        i.setPackage(getPackageName());
        i.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        sendBroadcast(i);
    }

    private void updateStatus() {
        if (mStatus == null) {
            return;
        }
        boolean running = MotionWakeService.sRunning;
        long idleMs = MotionWakeService.sIdleMs;
        long last = MotionWakeService.sLastMotionWall;
        StringBuilder sb = new StringBuilder();
        sb.append("Monitor: ");
        sb.append(running ? getString(R.string.running) : getString(R.string.stopped));
        if (running) {
            sb.append("\n").append(getString(R.string.idle, idleMs / 1000));
            if (last > 0) {
                long since = System.currentTimeMillis() - last;
                sb.append("\n").append(getString(R.string.last_motion, since / 1000));
            } else {
                sb.append("\n  ").append(getString(R.string.no_motion_yet));
            }
        }
        mStatus.setText(sb);
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPoll.removeCallbacks(mPollRunnable);
        updateStatus();
        mPoll.post(mPollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPoll.removeCallbacks(mPollRunnable);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void copyTokenToClipboard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && mApiTokenView != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "motionwake-token", mApiTokenView.getText().toString()));
                toast(R.string.token_copied);
            }
        } catch (Exception ignored) {
        }
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static float parseFloat(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return -1f;
        }
    }

    private static String formatNumber(float v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v) + ".0";
        }
        return String.valueOf(v);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int width, int height, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = topMargin;
        return p;
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                p.leftMargin = dp(8);
            }
            l.addView(buttons[i], p);
        }
        return l;
    }

    private Button button(int resId, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(resId);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    private Button buttonFull(int resId, View.OnClickListener click) {
        return button(resId, click);
    }
}
