package com.kaua.monitortermico;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    public static final String ACTION_START_SESSION = "com.kaua.monitortermico.START_SESSION";
    public static final String ACTION_STOP_SESSION = "com.kaua.monitortermico.STOP_SESSION";
    public static final String ACTION_REFRESH_DISPLAY = "com.kaua.monitortermico.REFRESH_DISPLAY";
    public static final String PREFS = "monitor_session";
    public static final String DISPLAY_PREFS = "monitor_display";

    private static final String CHANNEL = "thermal_monitor_v6";
    private static final int NOTIFICATION_ID = 77;

    private ScheduledExecutorService executor;
    private WindowManager windowManager;
    private TextView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private JSONObject lastSnapshot;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_REFRESH_DISPLAY : intent.getAction();
        if (action == null) action = ACTION_REFRESH_DISPLAY;

        if (ACTION_START_SESSION.equals(action)) {
            startSessionInternal();
        } else if (ACTION_STOP_SESSION.equals(action)) {
            finishSession();
        }

        if (!needsService()) {
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(lastSnapshot));
        applyOverlayState();
        ensureExecutor();
        return START_STICKY;
    }

    private void startSessionInternal() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getBoolean("running", false)) return;
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

    private boolean needsService() {
        SharedPreferences session = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences display = getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        return session.getBoolean("running", false)
                || display.getBoolean("notificationEnabled", false)
                || (display.getBoolean("overlayEnabled", false) && canDrawOverlays());
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void ensureExecutor() {
        if (executor != null && !executor.isShutdown()) return;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(this::sample, 0, 2, TimeUnit.SECONDS);
    }

    private void sample() {
        try {
            JSONObject snap = ThermalReader.snapshot(this, null, null, null);
            lastSnapshot = snap;

            JSONObject battery = snap.optJSONObject("battery");
            JSONObject cpu = snap.optJSONObject("cpu");
            JSONObject gpu = snap.optJSONObject("gpu");
            JSONObject thermal = snap.optJSONObject("systemThermal");

            Double batt = getDouble(battery, "temperatureC");
            Double cpuT = getDouble(cpu, "temperatureC");
            Double gpuT = getDouble(gpu, "temperatureC");
            Double head = getDouble(thermal, "headroom");

            SharedPreferences session = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (session.getBoolean("running", false)) {
                SharedPreferences.Editor e = session.edit();
                e.putLong("samples", session.getLong("samples", 0) + 1);
                e.putLong("lastAt", System.currentTimeMillis());

                if (batt != null) e.putFloat("lastBattery", batt.floatValue());
                if (cpuT != null) e.putFloat("lastCpu", cpuT.floatValue());
                if (gpuT != null) e.putFloat("lastGpu", gpuT.floatValue());
                if (head != null) e.putFloat("lastHeadroom", head.floatValue());

                putMax(session, e, "maxBattery", batt);
                putMax(session, e, "maxCpu", cpuT);
                putMax(session, e, "maxGpu", gpuT);
                putMax(session, e, "maxHeadroom", head);
                e.apply();
            }

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(snap));

            runOnMain(() -> updateOverlay(snap));
        } catch (Exception ignored) {}
    }

    private void runOnMain(Runnable r) {
        new android.os.Handler(getMainLooper()).post(r);
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

    private String fmt(Double v) {
        return v == null ? "--" : String.format(Locale.US, "%.1f", v);
    }

    private double cpuFrequencyPercent(JSONObject cpu) {
        if (cpu == null) return Double.NaN;
        if (cpu.has("frequencyPercent")) {
            double v = cpu.optDouble("frequencyPercent", Double.NaN);
            if (!Double.isNaN(v)) return v;
        }
        JSONArray cores = cpu.optJSONArray("cores");
        if (cores == null) return Double.NaN;
        double cur = 0, max = 0;
        for (int i = 0; i < cores.length(); i++) {
            JSONObject c = cores.optJSONObject(i);
            if (c == null) continue;
            double a = c.optDouble("currentMHz", 0);
            double b = c.optDouble("maxMHz", 0);
            if (a > 0 && b > 0) {
                cur += Math.min(a, b);
                max += b;
            }
        }
        return max > 0 ? Math.max(0, Math.min(100, cur / max * 100.0)) : Double.NaN;
    }

    private void finishSession() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit()
                .putBoolean("running", false)
                .putLong("endedAt", System.currentTimeMillis())
                .apply();
    }

    private Notification buildNotification(JSONObject snap) {
        SharedPreferences settings = getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        SharedPreferences session = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean customEnabled = settings.getBoolean("notificationEnabled", false);
        boolean sessionRunning = session.getBoolean("running", false);
        String title = settings.getString("notificationTitle", "Monitor Térmico");
        String metric = settings.getString("notificationMetric", "both");
        boolean details = settings.getBoolean("notificationDetails", true);

        String text = "Monitoramento térmico ativo";
        String big = text;

        if (snap != null) {
            JSONObject cpu = snap.optJSONObject("cpu");
            JSONObject battery = snap.optJSONObject("battery");
            Double cpuT = getDouble(cpu, "temperatureC");
            Double battT = getDouble(battery, "temperatureC");
            boolean estimated = cpu != null && cpu.optBoolean("estimated", false);

            if (customEnabled) {
                if ("cpu".equals(metric)) {
                    text = "CPU " + fmt(cpuT) + "°C" + (estimated ? " (estimada)" : "");
                } else if ("battery".equals(metric)) {
                    text = "Bateria " + fmt(battT) + "°C";
                } else {
                    text = "CPU " + fmt(cpuT) + "°C • Bateria " + fmt(battT) + "°C";
                }

                if (details) {
                    StringBuilder sb = new StringBuilder(text);
                    if (battery != null && battery.has("levelPct")) {
                        sb.append(" • ").append(battery.optInt("levelPct")).append("% bateria");
                    }
                    double freq = cpuFrequencyPercent(cpu);
                    if (!Double.isNaN(freq)) {
                        sb.append(" • CPU ").append(Math.round(freq)).append("%");
                    }
                    if (estimated && !"battery".equals(metric)) sb.append(" • temperatura estimada");
                    big = sb.toString();
                } else {
                    big = text;
                }
            } else if (sessionRunning) {
                title = "Sessão térmica ativa";
                text = "CPU " + fmt(cpuT) + "°C • Bateria " + fmt(battT) + "°C";
                big = text;
            } else {
                title = "Monitor Térmico";
                text = "Exibição flutuante ativa";
                big = text;
            }
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(buildCpuNotificationSmallIcon())
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setStyle(new Notification.BigTextStyle().bigText(big));

        return b.build();
    }


    /**
     * Ícone branco/monocromático de CPU para a barra de status e notificação.
     *
     * O Android usa apenas a máscara alfa do small icon e aplica a cor do sistema.
     * Por isso o desenho é simples, grosso e sem fundo, para continuar legível
     * tanto na pré-visualização fechada quanto na barra de notificações.
     */
    private Icon buildCpuNotificationSmallIcon() {
        final int size = 128;

        Bitmap bitmap = Bitmap.createBitmap(
                size,
                size,
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);

        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setColor(Color.TRANSPARENT);
        clear.setStyle(Paint.Style.FILL);
        clear.setXfermode(
                new android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.CLEAR
                )
        );

        // Corpo principal do chip.
        RectF body = new RectF(28f, 28f, 100f, 100f);
        canvas.drawRoundRect(body, 14f, 14f, fill);

        // Recorte central para formar o contorno do chip.
        RectF inner = new RectF(43f, 43f, 85f, 85f);
        canvas.drawRoundRect(inner, 6f, 6f, clear);

        // Pinos: poucos, grossos e simétricos para aparecerem bem pequenos.
        float[] pinPos = {42f, 64f, 86f};

        for (float x : pinPos) {
            canvas.drawRoundRect(
                    new RectF(x - 4f, 7f, x + 4f, 26f),
                    4f,
                    4f,
                    fill
            );

            canvas.drawRoundRect(
                    new RectF(x - 4f, 102f, x + 4f, 121f),
                    4f,
                    4f,
                    fill
            );
        }

        for (float y : pinPos) {
            canvas.drawRoundRect(
                    new RectF(7f, y - 4f, 26f, y + 4f),
                    4f,
                    4f,
                    fill
            );

            canvas.drawRoundRect(
                    new RectF(102f, y - 4f, 121f, y + 4f),
                    4f,
                    4f,
                    fill
            );
        }

        // Pequeno núcleo no centro para reforçar o símbolo de processador.
        RectF core = new RectF(54f, 54f, 74f, 74f);
        canvas.drawRoundRect(core, 4f, 4f, fill);

        return Icon.createWithBitmap(bitmap);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL,
                    "Temperatura em tempo real",
                    NotificationManager.IMPORTANCE_LOW
            );
            c.setDescription("Mostra CPU, bateria e mantém a exibição flutuante ativa.");
            c.enableVibration(false);
            c.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private void applyOverlayState() {
        SharedPreferences p = getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        if (p.getBoolean("overlayEnabled", false) && canDrawOverlays()) {
            ensureOverlay();
            updateOverlay(lastSnapshot);
        } else {
            removeOverlay();
        }
    }

    private void ensureOverlay() {
        if (overlayView != null || windowManager == null || !canDrawOverlays()) return;

        overlayView = new TextView(this);
        overlayView.setGravity(Gravity.CENTER);
        overlayView.setSingleLine(false);
        overlayView.setPadding(dp(8), dp(4), dp(8), dp(4));
        overlayView.setElevation(dp(8));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        overlayParams = new WindowManager.LayoutParams(
                dp(64),
                dp(54),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        SharedPreferences p = getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        overlayParams.y = Math.max(dp(24), p.getInt("overlayY", dp(140)));
        overlayParams.x = sideX(p.getString("overlaySide", "left"), dp(64));

        overlayView.setOnTouchListener(new OverlayTouchListener());
        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Exception ignored) {
            overlayView = null;
            overlayParams = null;
        }
    }

    private int sideX(String side, int width) {
        if (!"right".equals(side)) return dp(8);
        int screen = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(8), screen - width - dp(8));
    }

    private void updateOverlay(JSONObject snap) {
        if (overlayView == null || overlayParams == null) {
            applyOverlayStateIfNeeded();
            return;
        }

        SharedPreferences p = getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        if (!p.getBoolean("overlayEnabled", false) || !canDrawOverlays()) {
            removeOverlay();
            return;
        }

        String metric = p.getString("overlayMetric", "cpu");
        String size = p.getString("overlaySize", "medium");
        String style = p.getString("overlayStyle", "dark");
        int opacity = p.getInt("overlayOpacity", 92);

        Double cpuT = null, battT = null;
        boolean estimated = false;
        if (snap != null) {
            JSONObject cpu = snap.optJSONObject("cpu");
            JSONObject battery = snap.optJSONObject("battery");
            cpuT = getDouble(cpu, "temperatureC");
            battT = getDouble(battery, "temperatureC");
            estimated = cpu != null && cpu.optBoolean("estimated", false);
        }

        String text;
        if ("battery".equals(metric)) {
            text = fmt0(battT) + "°";
        } else if ("both".equals(metric)) {
            text = "CPU " + fmt0(cpuT) + "°\nBAT " + fmt0(battT) + "°";
        } else {
            text = fmt0(cpuT) + "°" + (estimated ? "~" : "");
        }
        overlayView.setText(text);

        int width, height;
        float textSp;
        if ("small".equals(size)) {
            width = "both".equals(metric) ? dp(74) : dp(50);
            height = "both".equals(metric) ? dp(48) : dp(46);
            textSp = "both".equals(metric) ? 11f : 15f;
        } else if ("large".equals(size)) {
            width = "both".equals(metric) ? dp(108) : dp(76);
            height = "both".equals(metric) ? dp(70) : dp(66);
            textSp = "both".equals(metric) ? 16f : 23f;
        } else {
            width = "both".equals(metric) ? dp(90) : dp(62);
            height = "both".equals(metric) ? dp(58) : dp(54);
            textSp = "both".equals(metric) ? 13f : 19f;
        }
        overlayView.setTextSize(textSp);
        overlayView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(17));
        if ("accent".equals(style)) {
            bg.setColor(Color.rgb(102, 126, 234));
            bg.setStroke(dp(1), Color.argb(120, 255, 255, 255));
            overlayView.setTextColor(Color.WHITE);
        } else if ("light".equals(style)) {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), Color.rgb(225, 226, 238));
            overlayView.setTextColor(Color.rgb(35, 39, 54));
        } else {
            bg.setColor(Color.rgb(28, 29, 34));
            bg.setStroke(dp(1), Color.rgb(67, 68, 78));
            overlayView.setTextColor(Color.WHITE);
        }
        overlayView.setBackground(bg);
        overlayView.setAlpha(Math.max(0.45f, Math.min(1f, opacity / 100f)));

        boolean sizeChanged = overlayParams.width != width || overlayParams.height != height;
        overlayParams.width = width;
        overlayParams.height = height;
        if (sizeChanged) {
            String side = p.getString("overlaySide", "left");
            overlayParams.x = sideX(side, width);
        }

        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception ignored) {}
    }

    private void applyOverlayStateIfNeeded() {
        SharedPreferences p = getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        if (p.getBoolean("overlayEnabled", false) && canDrawOverlays()) ensureOverlay();
    }

    private String fmt0(Double v) {
        return v == null ? "--" : String.valueOf(Math.round(v));
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {}
        }
        overlayView = null;
        overlayParams = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class OverlayTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private long downAt;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (overlayParams == null || windowManager == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = overlayParams.x;
                    startY = overlayParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    downAt = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    overlayParams.x = startX + Math.round(event.getRawX() - downX);
                    overlayParams.y = Math.max(dp(18), startY + Math.round(event.getRawY() - downY));
                    try {
                        windowManager.updateViewLayout(overlayView, overlayParams);
                    } catch (Exception ignored) {}
                    return true;

                case MotionEvent.ACTION_UP:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    boolean tap = Math.hypot(dx, dy) < dp(7) && System.currentTimeMillis() - downAt < 350;

                    if (tap) {
                        try {
                            Intent open = new Intent(MonitorService.this, MainActivity.class);
                            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(open);
                        } catch (Exception ignored) {}
                    } else {
                        int screen = getResources().getDisplayMetrics().widthPixels;
                        String side = event.getRawX() < screen / 2f ? "left" : "right";
                        overlayParams.x = sideX(side, overlayParams.width);
                        getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE).edit()
                                .putString("overlaySide", side)
                                .putInt("overlayY", overlayParams.y)
                                .apply();
                        try {
                            windowManager.updateViewLayout(overlayView, overlayParams);
                        } catch (Exception ignored) {}
                    }
                    return true;
            }
            return false;
        }
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
