package com.kaua.monitortermico;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ThermalReader {
    private ThermalReader() {}

    private static final Object HEADROOM_LOCK = new Object();
    private static long lastHeadroomAt = 0L;
    private static Double cachedHeadroom = null;

    private static final Object SENSOR_LOCK = new Object();
    private static long lastDiscoveryAt = 0L;
    private static List<SensorPath> cachedReadablePaths = new ArrayList<>();
    private static int cachedScannedPaths = 0;
    private static final long RESCAN_WITH_RESULTS_MS = 5 * 60_000L;
    private static final long RESCAN_WITHOUT_RESULTS_MS = 30_000L;

    private static final String[] LEGACY_CPU_PATHS = new String[]{
            "/sys/class/hwmon/hwmon0/device/temp1_input",
            "/sys/class/i2c-adapter/i2c-4/4-004c/temperature",

            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp",
            "/sys/class/thermal/thermal_zone3/temp",

            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone2/temp",
            "/sys/devices/virtual/thermal/thermal_zone3/temp",

            "/sys/devices/system/cpu/cpu0/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu1/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu2/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu3/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu4/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu5/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu6/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu7/cpufreq/cpu_temp",
            "/sys/devices/system/cpu/cpu0/cpufreq/FakeShmoo_cpu_temp",

            "/sys/devices/platform/omap/omap_temp_sensor.0/temperature",
            "/sys/devices/platform/s5p-tmu/curr_temp",
            "/sys/devices/platform/s5p-tmu/temperature",
            "/sys/devices/platform/tegra-i2c.3/i2c-4/4-004c/ext_temperature",
            "/sys/devices/platform/tegra-i2c.3/i2c-4/4-004c/temperature",
            "/sys/devices/platform/tegra-thermal/thermal_zone0/temp",
            "/sys/devices/platform/tegra-thermal/thermal_zone1/temp",
            "/sys/devices/platform/tegra-thermal/thermal_zone2/temp",
            "/sys/devices/platform/tegra-thermal/thermal_zone3/temp",
            "/sys/devices/platform/tegra-tsensor/tsensor_temperature",
            "/sys/devices/platform/tegra_tmon/temp1_input",
            "/sys/kernel/debug/tegra_thermal/temp_tj",
            "/sys/htc/cpu_temp"
    };

    public static void forceRescan() {
        synchronized (SENSOR_LOCK) {
            lastDiscoveryAt = 0L;
            cachedReadablePaths = new ArrayList<>();
            cachedScannedPaths = 0;
        }
    }

    public static JSONObject snapshot(Context context, Double ambientTemp, Double humidity, Double lightLux) {
        JSONObject root = new JSONObject();
        try {
            root.put("timestamp", System.currentTimeMillis());
            root.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            root.put("android", Build.VERSION.RELEASE);

            JSONObject battery = readBattery(context);
            root.put("battery", battery);

            Discovery discovery = readThermalZones();
            root.put("thermalZones", zonesToJson(discovery.zones));
            root.put("cpu", readCpu(discovery.zones, getJsonDouble(battery, "temperatureC")));
            root.put("gpu", readGpu(discovery.zones));
            root.put("systemThermal", readSystemThermal(context));
            root.put("environment", environmentJson(ambientTemp, humidity, lightLux));

            JSONObject direct = new JSONObject();
            direct.put("mode", "Leitura direta /sys");
            direct.put("requiresShizuku", false);
            direct.put("scannedPaths", discovery.scannedPaths);
            direct.put("readablePaths", discovery.zones.size());
            direct.put("cached", discovery.cached);
            root.put("directProbe", direct);
        } catch (Exception ignored) {}
        return root;
    }

    private static JSONObject environmentJson(Double temp, Double humidity, Double light) {
        JSONObject o = new JSONObject();
        try {
            if (temp != null) o.put("temperatureC", round1(temp));
            if (humidity != null) o.put("humidityPct", round1(humidity));
            if (light != null) o.put("lightLux", Math.round(light));
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject readBattery(Context context) {
        JSONObject o = new JSONObject();
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return o;

            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            boolean present = battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
            String technology = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

            if (level >= 0 && scale > 0) o.put("levelPct", Math.round(level * 100f / scale));
            if (temp != Integer.MIN_VALUE) o.put("temperatureC", round1(temp / 10.0));
            if (voltageMv > 0) o.put("voltageV", round3(voltageMv / 1000.0));
            o.put("health", batteryHealth(health));
            o.put("status", batteryStatus(status));
            o.put("plugged", pluggedLabel(plugged));
            o.put("present", present);
            if (technology != null) o.put("technology", technology);

            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                long currentNow = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                long currentAvg = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                long chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                long energyCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);

                if (validBatteryProperty(currentNow)) {
                    double currentA = currentNow / 1_000_000.0;
                    o.put("currentA", round3(currentA));
                    if (voltageMv > 0) o.put("powerW", round2(Math.abs(currentA * (voltageMv / 1000.0))));
                }
                if (validBatteryProperty(currentAvg)) o.put("currentAverageA", round3(currentAvg / 1_000_000.0));
                if (validBatteryProperty(chargeCounter)) o.put("chargeRemainingMah", Math.round(chargeCounter / 1000.0));
                if (validBatteryProperty(energyCounter)) o.put("energyRemainingWh", round2(energyCounter / 1_000_000_000.0));
            }
        } catch (Exception ignored) {}
        return o;
    }

    private static boolean validBatteryProperty(long v) {
        return v != Long.MIN_VALUE && v != Integer.MIN_VALUE && Math.abs(v) < Long.MAX_VALUE / 2;
    }

    private static JSONObject readSystemThermal(Context context) {
        JSONObject o = new JSONObject();
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return o;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int status = pm.getCurrentThermalStatus();
                o.put("statusCode", status);
                o.put("status", thermalStatusLabel(status));
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Double headroom = getHeadroom(pm);
                if (headroom != null && !headroom.isNaN() && !headroom.isInfinite()) {
                    o.put("headroom", round2(headroom));
                }
            }
        } catch (Exception ignored) {}
        return o;
    }

    private static Double getHeadroom(PowerManager pm) {
        synchronized (HEADROOM_LOCK) {
            long now = System.currentTimeMillis();
            if (cachedHeadroom != null && now - lastHeadroomAt < 10_000L) return cachedHeadroom;

            try {
                float value = pm.getThermalHeadroom(0);
                lastHeadroomAt = now;
                if (Float.isNaN(value) || Float.isInfinite(value)) cachedHeadroom = null;
                else cachedHeadroom = (double) value;
            } catch (Exception e) {
                lastHeadroomAt = now;
                cachedHeadroom = null;
            }
            return cachedHeadroom;
        }
    }

    private static JSONObject readCpu(List<ThermalZone> zones, Double batteryTemp) {
        JSONObject o = new JSONObject();
        try {
            ThermalZone best = null;

            // 1) Prioridade máxima: um sensor real identificado como CPU / SoC.
            for (ThermalZone z : zones) {
                if (!"CPU / SoC".equals(z.category)) continue;
                if (best == null || cpuScore(z) > cpuScore(best) ||
                        (cpuScore(z) == cpuScore(best) && z.tempC > best.tempC)) {
                    best = z;
                }
            }

            // 2) Se o kernel deixa ler thermal_zoneN/temp mas esconde o "type",
            // usa um candidato plausível e sinaliza que a identificação não é garantida.
            if (best == null) {
                for (ThermalZone z : zones) {
                    if (!z.anonymous) continue;
                    if (z.tempC < 25 || z.tempC > 125) continue;
                    if (batteryTemp != null && Math.abs(z.tempC - batteryTemp) <= 0.8) continue;

                    if (best == null || anonymousCpuScore(z, batteryTemp) > anonymousCpuScore(best, batteryTemp)) {
                        best = z;
                    }
                }
            }

            JSONArray cores = readCpuFrequencies();

            if (best != null) {
                o.put("temperatureC", round1(best.tempC));
                o.put("sensor", best.name);
                o.put("source", best.source);
                o.put("path", best.path);
                o.put("probable", best.anonymous);
                o.put("estimated", false);
                o.put("confidence", best.anonymous ? "provável" : "identificado");
            } else if (batteryTemp != null) {
                // 3) Fallback sem sensor físico:
                // técnica equivalente à observada no app de referência enviado pelo usuário.
                // Ele usa a temperatura inteira da bateria e acrescenta aproximadamente
                // 0,1 °C por ponto percentual da razão frequência atual / frequência máxima.
                double frequencyPercent = cpuFrequencyPercent(cores);
                int batteryWholeC = (int) batteryTemp.doubleValue();
                double estimateC = Math.round(batteryWholeC + (frequencyPercent / 10.0));

                o.put("temperatureC", round1(estimateC));
                o.put("sensor", "Estimativa térmica da CPU");
                o.put("source", "bateria + frequência da CPU");
                o.put("probable", false);
                o.put("estimated", true);
                o.put("confidence", "estimada");
                o.put("frequencyPercent", round1(frequencyPercent));
                o.put("estimateBaseBatteryC", batteryWholeC);
            }

            o.put("cores", cores);
        } catch (Exception ignored) {}
        return o;
    }

    private static double cpuFrequencyPercent(JSONArray cores) {
        try {
            double currentSum = 0.0;
            double maxSum = 0.0;
            int valid = 0;

            for (int i = 0; i < cores.length(); i++) {
                JSONObject c = cores.optJSONObject(i);
                if (c == null || !c.has("currentMHz") || !c.has("maxMHz")) continue;

                double current = c.optDouble("currentMHz", 0.0);
                double max = c.optDouble("maxMHz", 0.0);

                if (current <= 0.0 || max <= 0.0) continue;

                currentSum += Math.min(current, max);
                maxSum += max;
                valid++;
            }

            if (valid == 0 || maxSum <= 0.0) {
                // O app de referência usa um valor intermediário quando ainda não há
                // histórico de frequência. Mantemos o mesmo tipo de fallback.
                return 50.0;
            }

            double pct = (currentSum / maxSum) * 100.0;
            if (pct < 0.0) pct = 0.0;
            if (pct > 100.0) pct = 100.0;
            return pct;
        } catch (Exception e) {
            return 50.0;
        }
    }

    private static int cpuScore(ThermalZone z) {
        String n = (z.name + " " + z.path).toLowerCase(Locale.ROOT);
        int s = 0;
        if (n.contains("cpu_temp") || n.contains("tscpu") || n.contains("mtktscpu")) s += 100;
        if (n.contains("cpu")) s += 80;
        if (n.contains("soc")) s += 75;
        if (n.contains("vproc")) s += 60;
        if (n.contains("cluster")) s += 55;
        if (n.contains("big") || n.contains("little")) s += 45;
        if (n.contains("ap_ntc") || n.endsWith(" ap")) s += 25;
        if (z.legacyCpuPath) s += 120;
        return s;
    }

    private static int anonymousCpuScore(ThermalZone z, Double batteryTemp) {
        int s = 0;
        String p = z.path.toLowerCase(Locale.ROOT);
        if (p.contains("/sys/class/thermal/thermal_zone")) s += 40;
        if (p.contains("/sys/devices/virtual/thermal/thermal_zone")) s += 35;

        int idx = thermalZoneIndex(p);
        if (idx >= 0) s += Math.max(0, 30 - idx);

        if (batteryTemp != null) {
            double delta = z.tempC - batteryTemp;
            if (delta >= 2 && delta <= 35) s += 30;
            else if (delta > 0.8) s += 10;
        }
        return s;
    }

    private static int thermalZoneIndex(String path) {
        try {
            int at = path.indexOf("thermal_zone");
            if (at < 0) return -1;
            int start = at + "thermal_zone".length();
            int end = start;
            while (end < path.length() && Character.isDigit(path.charAt(end))) end++;
            if (end <= start) return -1;
            return Integer.parseInt(path.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private static JSONObject readGpu(List<ThermalZone> zones) {
        JSONObject o = new JSONObject();
        try {
            ThermalZone best = null;
            for (ThermalZone z : zones) {
                if (!"GPU".equals(z.category)) continue;
                if (best == null || z.tempC > best.tempC) best = z;
            }
            if (best != null) {
                o.put("temperatureC", round1(best.tempC));
                o.put("sensor", best.name);
                o.put("source", best.source);
                o.put("path", best.path);
            }
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONArray readCpuFrequencies() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 16; i++) {
            try {
                String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
                String cur = firstReadablePath(base + "scaling_cur_freq", base + "cpuinfo_cur_freq");
                String max = firstReadablePath(base + "scaling_max_freq", base + "cpuinfo_max_freq");

                if (cur == null && max == null) continue;

                JSONObject c = new JSONObject();
                c.put("core", "cpu" + i);
                if (cur != null) c.put("currentMHz", khzToMhz(cur));
                if (max != null) c.put("maxMHz", khzToMhz(max));
                arr.put(c);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private static int khzToMhz(String s) {
        try {
            long v = Long.parseLong(s.trim());
            if (v > 100_000_000L) return (int) Math.round(v / 1_000_000.0);
            if (v > 100_000L) return (int) Math.round(v / 1000.0);
            return (int) v;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstReadablePath(String... paths) {
        for (String p : paths) {
            String s = readText(p);
            if (s != null && !s.trim().isEmpty()) return s.trim();
        }
        return null;
    }

    private static Discovery readThermalZones() {
        synchronized (SENSOR_LOCK) {
            long now = System.currentTimeMillis();
            long ttl = cachedReadablePaths.isEmpty() ? RESCAN_WITHOUT_RESULTS_MS : RESCAN_WITH_RESULTS_MS;
            boolean canUseCache = lastDiscoveryAt > 0 && now - lastDiscoveryAt < ttl;

            List<SensorPath> paths;
            int scanned;
            boolean cached;

            if (canUseCache) {
                paths = new ArrayList<>(cachedReadablePaths);
                scanned = cachedScannedPaths;
                cached = true;
            } else {
                DiscoveryPaths d = discoverReadablePaths();
                paths = d.paths;
                scanned = d.scanned;
                cachedReadablePaths = new ArrayList<>(paths);
                cachedScannedPaths = scanned;
                lastDiscoveryAt = now;
                cached = false;
            }

            List<ThermalZone> zones = readDiscoveredPaths(paths);

            // Se o cache ficou inválido após boot/update, força nova descoberta uma vez.
            if (cached && zones.isEmpty() && !paths.isEmpty()) {
                lastDiscoveryAt = 0L;
                cachedReadablePaths = new ArrayList<>();
                DiscoveryPaths d = discoverReadablePaths();
                cachedReadablePaths = new ArrayList<>(d.paths);
                cachedScannedPaths = d.scanned;
                lastDiscoveryAt = now;
                zones = readDiscoveredPaths(d.paths);
                scanned = d.scanned;
                cached = false;
            }

            zones.sort((a, b) -> {
                int ca = categoryRank(a.category);
                int cb = categoryRank(b.category);
                if (ca != cb) return Integer.compare(ca, cb);
                return Double.compare(b.tempC, a.tempC);
            });

            return new Discovery(zones, scanned, cached);
        }
    }

    private static DiscoveryPaths discoverReadablePaths() {
        List<SensorPath> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int scanned = 0;

        // Caminhos conhecidos encontrados em apps de monitoramento de CPU.
        for (String path : LEGACY_CPU_PATHS) {
            scanned++;
            addIfReadable(found, seen, path, "CPU / SoC", true);
        }

        // Não depende de listFiles(): tenta cada thermal_zone diretamente.
        for (int i = 0; i < 100; i++) {
            String p1 = "/sys/class/thermal/thermal_zone" + i + "/temp";
            scanned++;
            addIfReadable(found, seen, p1, null, false);

            String p2 = "/sys/devices/virtual/thermal/thermal_zone" + i + "/temp";
            scanned++;
            addIfReadable(found, seen, p2, null, false);
        }

        // Mesmo princípio para hwmon: tenta arquivos individualmente sem listar diretórios.
        for (int h = 0; h < 16; h++) {
            for (int t = 1; t <= 12; t++) {
                String p1 = "/sys/class/hwmon/hwmon" + h + "/temp" + t + "_input";
                scanned++;
                addIfReadable(found, seen, p1, null, false);

                String p2 = "/sys/class/hwmon/hwmon" + h + "/device/temp" + t + "_input";
                scanned++;
                addIfReadable(found, seen, p2, null, false);
            }
        }

        return new DiscoveryPaths(found, scanned);
    }

    private static void addIfReadable(List<SensorPath> out, Set<String> seen, String path, String forcedCategory, boolean legacyCpuPath) {
        if (!seen.add(path)) return;
        String raw = readText(path);
        if (parseTemperature(raw) == null) return;
        out.add(new SensorPath(path, forcedCategory, legacyCpuPath));
    }

    private static List<ThermalZone> readDiscoveredPaths(List<SensorPath> paths) {
        List<ThermalZone> result = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();

        for (SensorPath sp : paths) {
            Double temp = parseTemperature(readText(sp.path));
            if (temp == null) continue;

            SensorIdentity id = identifyPath(sp.path, sp.forcedCategory);
            String category = id.category;
            boolean anonymous = id.anonymous;
            String name = id.name;
            String key = canonicalSensorKey(sp.path, name, temp);

            if (!dedupe.add(key)) continue;
            result.add(new ThermalZone(name, temp, id.source, category, sp.path, anonymous, sp.legacyCpuPath));
        }
        return result;
    }

    private static String canonicalSensorKey(String path, String name, double temp) {
        String p = path.replace("/sys/devices/virtual/thermal/", "/sys/class/thermal/");
        return p.toLowerCase(Locale.ROOT) + "|" + name.toLowerCase(Locale.ROOT) + "|" + Math.round(temp * 10.0);
    }

    private static SensorIdentity identifyPath(String path, String forcedCategory) {
        String p = path.toLowerCase(Locale.ROOT);

        if (forcedCategory != null) {
            String label = shortPathLabel(path);
            return new SensorIdentity(label, forcedCategory, "arquivo direto", false);
        }

        if (p.contains("thermal_zone")) {
            String base = path.substring(0, path.length() - "/temp".length());
            String type = readText(base + "/type");
            String name = cleanLabel(type);
            if (name != null) {
                return new SensorIdentity(name, categoryFor(name), "thermal direto", false);
            }
            return new SensorIdentity(shortPathLabel(path), "Não identificado", "thermal direto", true);
        }

        if (p.contains("/hwmon")) {
            String labelPath = path.replaceFirst("temp(\\d+)_input$", "temp$1_label");
            String label = cleanLabel(readText(labelPath));

            String monBase = hwmonBase(path);
            String hwName = monBase == null ? null : cleanLabel(readText(monBase + "/name"));

            String name = label != null ? label : (hwName != null ? hwName : shortPathLabel(path));
            boolean anonymous = label == null && hwName == null;
            return new SensorIdentity(name, anonymous ? "Não identificado" : categoryFor(name), "hwmon direto", anonymous);
        }

        String name = shortPathLabel(path);
        String category = forcedCategory != null ? forcedCategory : categoryFor(name + " " + path);
        return new SensorIdentity(name, category, "arquivo direto", false);
    }

    private static String hwmonBase(String path) {
        int at = path.indexOf("/hwmon");
        if (at < 0) return null;
        int nextSlash = path.indexOf('/', at + 1);
        if (nextSlash < 0) return null;
        return path.substring(0, nextSlash);
    }

    private static String cleanLabel(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        return s;
    }

    private static String shortPathLabel(String path) {
        String p = path;
        if (p.endsWith("/temp")) p = p.substring(0, p.length() - 5);
        int slash = p.lastIndexOf('/');
        String leaf = slash >= 0 ? p.substring(slash + 1) : p;

        if (leaf.matches("cpu\\d+")) return leaf + " temperature";
        if (leaf.startsWith("thermal_zone")) return leaf;

        String parent = slash > 0 ? p.substring(0, slash) : "";
        int parentSlash = parent.lastIndexOf('/');
        String parentLeaf = parentSlash >= 0 ? parent.substring(parentSlash + 1) : parent;
        if (parentLeaf.matches("cpu\\d+")) return parentLeaf + " temperature";

        String file = path.substring(path.lastIndexOf('/') + 1);
        if (file.contains("cpu_temp")) return parentLeaf + " cpu_temp";
        return parentLeaf + " " + file;
    }

    private static Double parseTemperature(String rawTemp) {
        if (rawTemp == null) return null;
        try {
            double raw = Double.parseDouble(rawTemp.trim());
            double c = normalizeTemp(raw);
            if (c < -40 || c > 180) return null;
            return round1(c);
        } catch (Exception e) {
            return null;
        }
    }

    private static double normalizeTemp(double raw) {
        double abs = Math.abs(raw);
        if (abs >= 10000) return raw / 1000.0;
        if (abs >= 1000) return raw / 100.0;
        if (abs >= 200) return raw / 10.0;
        return raw;
    }

    private static JSONArray zonesToJson(List<ThermalZone> zones) {
        JSONArray arr = new JSONArray();
        for (ThermalZone z : zones) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", z.name);
                o.put("temperatureC", round1(z.tempC));
                o.put("source", z.source);
                o.put("category", z.category);
                o.put("path", z.path);
                o.put("anonymous", z.anonymous);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private static int categoryRank(String category) {
        if ("CPU / SoC".equals(category)) return 0;
        if ("GPU".equals(category)) return 1;
        if ("Bateria".equals(category)) return 2;
        if ("Carregamento".equals(category)) return 3;
        if ("Superfície".equals(category)) return 4;
        if ("Não identificado".equals(category)) return 8;
        return 6;
    }

    private static String categoryFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (isBatteryName(n)) return "Bateria";
        if (n.contains("gpu") || n.contains("mali")) return "GPU";
        if (isCpuName(n)) return "CPU / SoC";
        if (isChargerName(n)) return "Carregamento";
        if (isSkinName(n)) return "Superfície";
        if (n.contains("modem") || n.contains("md") || n.contains("pa")) return "Rede / modem";
        if (n.contains("camera")) return "Câmera";
        if (n.contains("wifi") || n.contains("wlan")) return "Wi‑Fi";
        if (n.contains("npu") || n.contains("apu")) return "NPU / APU";
        return "Outro";
    }

    private static boolean isCpuName(String n) {
        return n.contains("cpu") || n.contains("soc") || n.contains("tscpu") ||
                n.contains("mtktscpu") || n.contains("vproc") || n.contains("cluster") ||
                n.contains("big") || n.contains("little") || n.contains("ap_ntc") ||
                n.equals("ap") || n.contains("cpu-therm") || n.contains("cpu_therm") ||
                n.contains("cpuss") || n.contains("cpu0") || n.contains("cpu1") ||
                n.contains("cpu2") || n.contains("cpu3") || n.contains("cpu4") ||
                n.contains("cpu5") || n.contains("cpu6") || n.contains("cpu7");
    }

    private static boolean isBatteryName(String n) {
        return n.contains("battery") || n.contains("batt") || n.contains("bat_temp");
    }

    private static boolean isSkinName(String n) {
        return n.contains("skin") || n.contains("shell") || n.contains("surface") ||
                n.contains("case") || n.contains("quiet_therm");
    }

    private static boolean isChargerName(String n) {
        return n.contains("charger") || n.contains("usb") || n.contains("pmic") || n.contains("charge");
    }

    private static String readText(String path) {
        return readText(new File(path));
    }

    private static String readText(File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            return br.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static Double getJsonDouble(JSONObject o, String key) {
        if (o == null || !o.has(key)) return null;
        double v = o.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    private static String batteryHealth(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Boa";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Superaquecida";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Ruim";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Sobretensão";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Falha";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Muito fria";
            default: return "Desconhecida";
        }
    }

    private static String batteryStatus(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Carregando";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Descarregando";
            case BatteryManager.BATTERY_STATUS_FULL: return "Completa";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Não carregando";
            default: return "Desconhecido";
        }
    }

    private static String pluggedLabel(int p) {
        if (p == BatteryManager.BATTERY_PLUGGED_AC) return "Tomada";
        if (p == BatteryManager.BATTERY_PLUGGED_USB) return "USB";
        if (p == BatteryManager.BATTERY_PLUGGED_WIRELESS) return "Sem fio";
        return "Desconectado";
    }

    private static String thermalStatusLabel(int s) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Indisponível";
        switch (s) {
            case PowerManager.THERMAL_STATUS_NONE: return "Sem throttling";
            case PowerManager.THERMAL_STATUS_LIGHT: return "Leve";
            case PowerManager.THERMAL_STATUS_MODERATE: return "Moderado";
            case PowerManager.THERMAL_STATUS_SEVERE: return "Severo";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "Crítico";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "Emergência";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "Desligamento";
            default: return "Desconhecido";
        }
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static final class SensorPath {
        final String path;
        final String forcedCategory;
        final boolean legacyCpuPath;

        SensorPath(String path, String forcedCategory, boolean legacyCpuPath) {
            this.path = path;
            this.forcedCategory = forcedCategory;
            this.legacyCpuPath = legacyCpuPath;
        }
    }

    private static final class SensorIdentity {
        final String name;
        final String category;
        final String source;
        final boolean anonymous;

        SensorIdentity(String name, String category, String source, boolean anonymous) {
            this.name = name;
            this.category = category;
            this.source = source;
            this.anonymous = anonymous;
        }
    }

    private static final class ThermalZone {
        final String name;
        final double tempC;
        final String source;
        final String category;
        final String path;
        final boolean anonymous;
        final boolean legacyCpuPath;

        ThermalZone(String name, double tempC, String source, String category,
                    String path, boolean anonymous, boolean legacyCpuPath) {
            this.name = name;
            this.tempC = tempC;
            this.source = source;
            this.category = category;
            this.path = path;
            this.anonymous = anonymous;
            this.legacyCpuPath = legacyCpuPath;
        }
    }

    private static final class DiscoveryPaths {
        final List<SensorPath> paths;
        final int scanned;

        DiscoveryPaths(List<SensorPath> paths, int scanned) {
            this.paths = paths;
            this.scanned = scanned;
        }
    }

    private static final class Discovery {
        final List<ThermalZone> zones;
        final int scannedPaths;
        final boolean cached;

        Discovery(List<ThermalZone> zones, int scannedPaths, boolean cached) {
            this.zones = zones;
            this.scannedPaths = scannedPaths;
            this.cached = cached;
        }
    }
}
