package com.gk.marketflow;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native functions exposed to the bundled page as window.AndroidBridge. */
public class Bridge {

    /** The page can only reach the broker's API hosts through this bridge. */
    static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList("api.groww.in", "growwapi-assets.groww.in"));
    static final String PREFS = "marketflow";

    private final MainActivity activity;
    private final WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    Bridge(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    @JavascriptInterface
    public void http(final String id, final String method, final String url, final String headersJson, final String body) {
        pool.execute(() -> {
            int status = 0;
            String text;
            try {
                Uri u = Uri.parse(url);
                if (!"https".equals(u.getScheme()) || u.getHost() == null || !ALLOWED_HOSTS.contains(u.getHost())) {
                    throw new SecurityException("Host not allowed: " + u.getHost());
                }
                java.util.HashMap<String, String> headers = new java.util.HashMap<>();
                JSONObject h = new JSONObject(headersJson == null || headersJson.isEmpty() ? "{}" : headersJson);
                for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                    String k = it.next();
                    headers.put(k, h.getString(k));
                }
                Http.Result r = Http.request(method, url, headers, body);
                status = r.status;
                text = r.body;
            } catch (Exception e) {
                text = String.valueOf(e.getMessage());
            }
            final String js = "window.__mfHttpCb(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(text) + ")";
            activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
        });
    }

    /** Shows a notification immediately. Returns false when notifications are not permitted yet. */
    @JavascriptInterface
    public boolean notify(String title, String text) {
        if (!canNotify()) return false;
        AlertJobService.show(activity, title, text, (title + text).hashCode());
        return true;
    }

    @JavascriptInterface
    public boolean canNotify() {
        return Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public void requestNotifications() {
        activity.runOnUiThread(activity::requestNotificationPermission);
    }

    /** Hands the price alerts and the current API token to the background checker. */
    @JavascriptInterface
    public void syncAlerts(String alertsJson, String token) {
        SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString("alerts", alertsJson == null ? "[]" : alertsJson).putString("token", token == null ? "" : token).apply();
        AlertJobService.schedule(activity, alertsJson != null && alertsJson.length() > 2 && token != null && !token.isEmpty());
    }

    /** Ids of alerts that fired while the app was closed (cleared once read). */
    @JavascriptInterface
    public String takeTriggered() {
        SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = p.getString("triggered", "[]");
        p.edit().putString("triggered", "[]").apply();
        return t;
    }

    @JavascriptInterface
    public void keepScreenOn(final boolean on) {
        activity.runOnUiThread(() -> {
            if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @JavascriptInterface
    public String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
