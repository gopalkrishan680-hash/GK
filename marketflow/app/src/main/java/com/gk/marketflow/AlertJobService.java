package com.gk.marketflow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.TimeZone;

/**
 * Checks the user's price alerts roughly every 15 minutes during NSE market hours, even when the app is closed.
 * Android decides the exact timing, so background alerts can arrive a few minutes late.
 */
public class AlertJobService extends JobService {

    private static final int JOB_ID = 7301;
    private static final String CHANNEL = "alerts";

    static void createChannel(Context ctx) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(ctx.getString(R.string.channel_alerts_desc));
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    static void show(Context ctx, String title, String text, int id) {
        createChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            ctx.getSystemService(NotificationManager.class).notify(id, n);
        } catch (SecurityException ignored) {
            // notification permission was revoked
        }
    }

    static void schedule(Context ctx, boolean enable) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (!enable) {
            js.cancel(JOB_ID);
            return;
        }
        if (js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, AlertJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    private static boolean marketOpen() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        int dow = c.get(Calendar.DAY_OF_WEEK), min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && min >= 9 * 60 + 15 && min <= 15 * 60 + 35;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!marketOpen()) return false;
        new Thread(() -> {
            try {
                check();
            } catch (Exception ignored) {
                // try again on the next run
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    private void check() throws Exception {
        SharedPreferences p = getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        String token = p.getString("token", "");
        JSONArray alerts = new JSONArray(p.getString("alerts", "[]"));
        if (token.isEmpty() || alerts.length() == 0) return;

        StringBuilder symbols = new StringBuilder();
        for (int i = 0; i < alerts.length() && i < 50; i++) {
            JSONObject a = alerts.getJSONObject(i);
            String s = a.getString("ex") + "_" + a.getString("sym");
            if (symbols.indexOf(s) < 0) symbols.append(symbols.length() > 0 ? "," : "").append(s);
        }
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("Accept", "application/json");
        headers.put("X-API-VERSION", "1.0");
        String url = "https://api.groww.in/v1/live-data/ltp?segment=CASH&exchange_symbols=" + Uri.encode(symbols.toString());
        Http.Result r = Http.request("GET", url, headers, null);
        if (r.status != 200) return;
        JSONObject payload = new JSONObject(r.body).optJSONObject("payload");
        if (payload == null) return;

        JSONArray triggered = new JSONArray(p.getString("triggered", "[]"));
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject a = alerts.getJSONObject(i);
            double ltp = payload.optDouble(a.getString("ex") + "_" + a.getString("sym"), Double.NaN);
            double level = a.getDouble("price");
            boolean above = "above".equals(a.getString("op"));
            if (!Double.isNaN(ltp) && (above ? ltp >= level : ltp <= level)) {
                String title = a.optString("name", a.getString("sym")) + (above ? " crossed above " : " fell below ") + level;
                show(this, title, "Last price " + ltp + ". Open GK MarketFlow to see the buying and selling pressure.", a.getString("id").hashCode());
                triggered.put(a.getString("id"));
            } else {
                remaining.put(a);
            }
        }
        p.edit().putString("alerts", remaining.toString()).putString("triggered", triggered.toString()).apply();
    }
}
