package com.kaua.monitortermico;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    public static final String ACTION_STOP = "com.kaua.monitortermico.STOP";
    public static final String PREFS = "monitor_session";
    private static final String CHANNEL = "thermal_monitor";
    private static final int NOTIFICATION_ID = 77;
    private ScheduledExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            finishSession();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!p.getBoolean("running", false)) {
            p.edit()
                    .clear()
                    .putBoolean("running", true)
                    .putLong("startedAt", System.currentTimeMillis())
                    .putFloat("maxBattery", Float.NaN)
                    .putFloat("maxCpu", Float.NaN)
                    .putFloat("maxGpu", Float.NaN)
                    .putFloat("maxHeadroom", Float.NaN)
                    .putLong("samples", 0)
                    .apply();
        }

        startForeground(NOTIFICATION_ID, buildNotification("Iniciando monitoramento…"));

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(this::sample, 0, 5, TimeUnit.SECONDS);
        }

        return START_STICKY;
    }

    private void sample() {
        try {
            JSONObject snap = ThermalReader.snapshot(this, null, null, null);
            JSONObject battery = snap.optJSONObject("battery");
            JSONObject cpu = snap.optJSONObject("cpu");
            JSONObject gpu = snap.optJSONObject("gpu");
            JSONObject thermal = snap.optJSONObject("systemThermal");

            Double batt = getDouble(battery, "temperatureC");
            Double cpuT = getDouble(cpu, "temperatureC");
            Double gpuT = getDouble(gpu, "temperatureC");
            Double head = getDouble(thermal, "headroom");

            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            SharedPreferences.Editor e = p.edit();
            e.putLong("samples", p.getLong("samples", 0) + 1);
            e.putLong("lastAt", System.currentTimeMillis());

            if (batt != null) e.putFloat("lastBattery", batt.floatValue());
            if (cpuT != null) e.putFloat("lastCpu", cpuT.floatValue());
            if (gpuT != null) e.putFloat("lastGpu", gpuT.floatValue());
            if (head != null) e.putFloat("lastHeadroom", head.floatValue());

            putMax(p, e, "maxBattery", batt);
            putMax(p, e, "maxCpu", cpuT);
            putMax(p, e, "maxGpu", gpuT);
            putMax(p, e, "maxHeadroom", head);
            e.apply();

            String text;
            if (cpuT != null && batt != null) {
                text = "CPU " + fmt(cpuT) + "°C • Bateria " + fmt(batt) + "°C";
            } else if (batt != null) {
                text = "Bateria " + fmt(batt) + "°C • CPU ainda indisponível";
            } else {
                text = "Monitor térmico ativo";
            }

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    private void putMax(SharedPreferences p, SharedPreferences.Editor e, String key, Double value) {
        if (value == null) return;
        float old = p.getFloat(key, Float.NaN);
        if (Float.isNaN(old) || value > old) e.putFloat(key, value.floatValue());
    }

    private static Double getDouble(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        double v = o.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    private String fmt(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private void finishSession() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit()
                .putBoolean("running", false)
                .putLong("endedAt", System.currentTimeMillis())
                .apply();

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.kaua.monitortermico.R.drawable.ic_launcher)
                .setContentTitle("Monitor Térmico")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL,
                    "Monitoramento térmico",
                    NotificationManager.IMPORTANCE_LOW
            );
            c.setDescription("Mostra a sessão de temperatura em andamento.");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        finishSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
