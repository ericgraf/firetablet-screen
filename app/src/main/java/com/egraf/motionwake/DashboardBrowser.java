/*
 * DashboardBrowser — MotionWake's built-in, fullscreen kiosk browser.
 *
 * Why: the tablet's wall dashboard (e.g. the Home Assistant page) needs a
 * browser that WE control: it must open reliably from our service on wake
 * (no default-browser ambiguity), keep its own cookie/session storage (the
 * HA login survives across wakes, sleeps, reboots), and can serve as the
 * device home once the app is made persistent.
 *
 * Launched with EXTRA_URL (page to show) and optionally EXTRA_RELOAD
 * (force a fresh load even if the same URL is already current). Relaunching
 * an already-running instance goes through onNewIntent (singleTask).
 */
package com.egraf.motionwake;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class DashboardBrowser extends Activity {

    private static final String TAG = "DashboardBrowser";

    public static final String EXTRA_URL = "com.egraf.motionwake.extra.URL";
    public static final String EXTRA_RELOAD = "com.egraf.motionwake.extra.RELOAD";

    private WebView mWeb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // NOTE: no FLAG_KEEP_SCREEN_ON on purpose. This build can only be
        // forced to sleep through PowerManagerService's own screen-off
        // timeout (the "short timeout" trick in ScreenController), and a
        // window holding FLAG_KEEP_SCREEN_ON blocks exactly that. The
        // Service's wake lock and the long system timeout keep the display
        // on while the dashboard is visible anyway.

        mWeb = new WebView(this);
        WebSettings s = mWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(mWeb, true);
        }
        mWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "page finished: " + url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                Log.w(TAG, "onReceivedError " + errorCode + " " + description
                        + " url=" + failingUrl);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                    WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if (scheme == null
                        || scheme.equals("http") || scheme.equals("https")
                        || scheme.equals("javascript")) {
                    return false; // keep it in the webview
                }
                // Other schemes: hand off to the system.
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "shouldOverrideUrlLoading handoff failed", e);
                }
                return true;
            }
        });
        mWeb.setBackgroundColor(Color.BLACK);
        setContentView(mWeb, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        String url = intentUrl(getIntent());
        if (url == null || url.length() == 0) {
            url = storedDashboardUrl();
        }
        if (url != null && url.length() > 0) {
            Log.i(TAG, "loading " + url);
            mWeb.loadUrl(url);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mWeb == null || intent == null) {
            return;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || url.length() == 0) {
            url = null;
        }
        boolean reload = intent.getBooleanExtra(EXTRA_RELOAD, false);
        String cur = mWeb.getUrl();
        if (url != null && (cur == null || !url.equals(cur))) {
            Log.i(TAG, "relaunch: loading " + url);
            mWeb.loadUrl(url);
        } else if (reload) {
            Log.i(TAG, "relaunch: reloading " + cur);
            mWeb.reload();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private static String intentUrl(Intent i) {
        if (i == null) {
            return null;
        }
        String url = i.getStringExtra(EXTRA_URL);
        if (url != null && url.length() > 0) {
            return url;
        }
        Uri d = i.getData();
        return d == null ? null : d.toString();
    }

    private String storedDashboardUrl() {
        try {
            SharedPreferences p = getSharedPreferences(
                    MotionWakeService.PREFS_NAME, MODE_PRIVATE);
            String url = p.getString(MotionWakeService.PREF_BROWSER_URL, "");
            return (url == null || url.length() == 0) ? null : url;
        } catch (Exception e) {
            return null;
        }
    }
}
