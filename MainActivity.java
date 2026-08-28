package com.kaua.monitortermico;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
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
        w.setStatusBarColor(0xFF000000);
        w.setNavigationBarColor(0xFF000000);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF000000);
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
            Intent i = new Intent(MainActivity.this, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        }

        @JavascriptInterface
        public void stopSession() {
            Intent i = new Intent(MainActivity.this, MonitorService.class);
            i.setAction(MonitorService.ACTION_STOP);
            startService(i);
        }

        @JavascriptInterface
        public String getSessionStats() {
            android.content.SharedPreferences p = getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE);
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

        private void putFloatIfPresent(JSONObject o, android.content.SharedPreferences p, String key) {
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
