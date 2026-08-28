package com.kaua.monitortermico;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class MainActivity extends Activity implements SensorEventListener {
    private WebView webView;
    private SensorManager sensorManager;
    private Double ambientTemp = null;
    private Double humidity = null;
    private Double lightLux = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(102, 126, 234));
        w.setNavigationBarColor(Color.rgb(248, 247, 252));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(102, 126, 234));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new NativeBridge(), "Native");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9001);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerUsefulSensors();

        SharedPreferences display = getSharedPreferences(MonitorService.DISPLAY_PREFS, MODE_PRIVATE);
        if (display.getBoolean("overlayEnabled", false) && canDrawOverlays()) {
            requestDisplayServiceRefresh();
        }

        if (webView != null) {
            webView.evaluateJavascript("window.onNativeResume && window.onNativeResume()", null);
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("Native");
            webView.destroy();
        }
        super.onDestroy();
    }

    private void registerUsefulSensors() {
        if (sensorManager == null) return;
        registerSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        registerSensor(Sensor.TYPE_RELATIVE_HUMIDITY);
        registerSensor(Sensor.TYPE_LIGHT);
    }

    private void registerSensor(int type) {
        Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor != null) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null || event.values.length == 0) return;
        double v = event.values[0];
        switch (event.sensor.getType()) {
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                ambientTemp = v;
                break;
            case Sensor.TYPE_RELATIVE_HUMIDITY:
                humidity = v;
                break;
            case Sensor.TYPE_LIGHT:
                lightLux = v;
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent i = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(i);
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Exception ignoredAgain) {}
        }
    }

    private void startServiceWithAction(String action) {
        Intent i = new Intent(this, MonitorService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    private void requestDisplayServiceRefresh() {
        SharedPreferences display = getSharedPreferences(MonitorService.DISPLAY_PREFS, MODE_PRIVATE);
        SharedPreferences session = getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE);
        boolean need = display.getBoolean("notificationEnabled", false)
                || (display.getBoolean("overlayEnabled", false) && canDrawOverlays())
                || session.getBoolean("running", false);

        Intent i = new Intent(this, MonitorService.class);
        i.setAction(MonitorService.ACTION_REFRESH_DISPLAY);
        if (need) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } else {
            startService(i);
        }
    }

    public final class NativeBridge {
        @JavascriptInterface
        public String getSnapshot() {
            return ThermalReader.snapshot(MainActivity.this, ambientTemp, humidity, lightLux).toString();
        }

        @JavascriptInterface
        public void forceRescan() {
            ThermalReader.forceRescan();
        }

        @JavascriptInterface
        public void startSession() {
            startServiceWithAction(MonitorService.ACTION_START_SESSION);
        }

        @JavascriptInterface
        public void stopSession() {
            Intent i = new Intent(MainActivity.this, MonitorService.class);
            i.setAction(MonitorService.ACTION_STOP_SESSION);
            startService(i);
        }

        @JavascriptInterface
        public boolean canDrawOverlays() {
            return MainActivity.this.canDrawOverlays();
        }

        @JavascriptInterface
        public void openOverlayPermission() {
            openOverlaySettings();
        }

        @JavascriptInterface
        public String getDisplaySettings() {
            SharedPreferences p = getSharedPreferences(MonitorService.DISPLAY_PREFS, MODE_PRIVATE);
            JSONObject o = new JSONObject();
            try {
                o.put("notificationEnabled", p.getBoolean("notificationEnabled", false));
                o.put("notificationMetric", p.getString("notificationMetric", "both"));
                o.put("notificationTitle", p.getString("notificationTitle", "Monitor Térmico"));
                o.put("notificationDetails", p.getBoolean("notificationDetails", true));

                o.put("overlayEnabled", p.getBoolean("overlayEnabled", false));
                o.put("overlayMetric", p.getString("overlayMetric", "cpu"));
                o.put("overlaySize", p.getString("overlaySize", "medium"));
                o.put("overlayOpacity", p.getInt("overlayOpacity", 92));
                o.put("overlayStyle", p.getString("overlayStyle", "dark"));
                o.put("overlaySide", p.getString("overlaySide", "left"));
                o.put("overlayPermission", MainActivity.this.canDrawOverlays());
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String saveDisplaySettings(String json) {
            JSONObject result = new JSONObject();
            try {
                JSONObject in = new JSONObject(json == null ? "{}" : json);
                SharedPreferences.Editor e = getSharedPreferences(MonitorService.DISPLAY_PREFS, MODE_PRIVATE).edit();

                if (in.has("notificationEnabled")) e.putBoolean("notificationEnabled", in.optBoolean("notificationEnabled", false));
                if (in.has("notificationMetric")) e.putString("notificationMetric", safeChoice(in.optString("notificationMetric"), "both", "cpu", "battery", "both"));
                if (in.has("notificationTitle")) {
                    String title = in.optString("notificationTitle", "Monitor Térmico").trim();
                    if (title.length() == 0) title = "Monitor Térmico";
                    if (title.length() > 40) title = title.substring(0, 40);
                    e.putString("notificationTitle", title);
                }
                if (in.has("notificationDetails")) e.putBoolean("notificationDetails", in.optBoolean("notificationDetails", true));

                if (in.has("overlayEnabled")) e.putBoolean("overlayEnabled", in.optBoolean("overlayEnabled", false));
                if (in.has("overlayMetric")) e.putString("overlayMetric", safeChoice(in.optString("overlayMetric"), "cpu", "cpu", "battery", "both"));
                if (in.has("overlaySize")) e.putString("overlaySize", safeChoice(in.optString("overlaySize"), "medium", "small", "medium", "large"));
                if (in.has("overlayStyle")) e.putString("overlayStyle", safeChoice(in.optString("overlayStyle"), "dark", "dark", "accent", "light"));
                if (in.has("overlaySide")) e.putString("overlaySide", safeChoice(in.optString("overlaySide"), "left", "left", "right"));
                if (in.has("overlayOpacity")) {
                    int opacity = Math.max(45, Math.min(100, in.optInt("overlayOpacity", 92)));
                    e.putInt("overlayOpacity", opacity);
                }
                e.apply();

                boolean overlayEnabled = getSharedPreferences(MonitorService.DISPLAY_PREFS, MODE_PRIVATE)
                        .getBoolean("overlayEnabled", false);
                boolean permission = MainActivity.this.canDrawOverlays();

                if (overlayEnabled && !permission) {
                    openOverlaySettings();
                }

                requestDisplayServiceRefresh();
                result.put("ok", true);
                result.put("overlayPermission", permission);
                result.put("needsOverlayPermission", overlayEnabled && !permission);
            } catch (Exception ex) {
                try {
                    result.put("ok", false);
                    result.put("error", ex.getClass().getSimpleName());
                } catch (Exception ignored) {}
            }
            return result.toString();
        }

        private String safeChoice(String value, String fallback, String... allowed) {
            for (String a : allowed) if (a.equals(value)) return value;
            return fallback;
        }

        @JavascriptInterface
        public String getSessionStats() {
            SharedPreferences p = getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE);
            JSONObject o = new JSONObject();
            try {
                o.put("running", p.getBoolean("running", false));
                o.put("startedAt", p.getLong("startedAt", 0));
                o.put("endedAt", p.getLong("endedAt", 0));
                o.put("samples", p.getLong("samples", 0));
                putFloatIfPresent(o, p, "maxBattery");
                putFloatIfPresent(o, p, "maxCpu");
                putFloatIfPresent(o, p, "maxGpu");
                putFloatIfPresent(o, p, "maxHeadroom");
                putFloatIfPresent(o, p, "lastBattery");
                putFloatIfPresent(o, p, "lastCpu");
                putFloatIfPresent(o, p, "lastGpu");
                putFloatIfPresent(o, p, "lastHeadroom");
            } catch (Exception ignored) {}
            return o.toString();
        }

        private void putFloatIfPresent(JSONObject o, SharedPreferences p, String key) {
            if (!p.contains(key)) return;
            float v = p.getFloat(key, Float.NaN);
            if (!Float.isNaN(v)) {
                try {
                    o.put(key, Math.round(v * 10f) / 10f);
                } catch (Exception ignored) {}
            }
        }
    }
}
