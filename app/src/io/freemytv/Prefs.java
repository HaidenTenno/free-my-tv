package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Stable storage codes for the buttons on this TV's remote. */
    static final int[] KEYS = RemoteProfile.codes();

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        migrateKeys();   // before anything reads the button choices
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt", "en", "es", "de" or "ru". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("ru") ? "ru" : v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("de") ? "de"
                : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City

    static String city() { return sp().getString("city", Config.CITY).trim(); }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude).apply();
        version++;
    }

    // MARK: - Home shortcuts, widgets and coins

    /** Controls home-screen shortcuts only; remote-button assignments remain independent. */
    static boolean liveTv() { return sp().getBoolean("show_live_tv", true); }
    static void setLiveTv(boolean on) { put("show_live_tv", on); }

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        RemoteProfile.Button button = RemoteProfile.find(keyCode);
        return button == null ? "" : button.defaultApp;
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final Map<Integer, String> keyApps = new LinkedHashMap<>();   // absent = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> entry : old.getAll().entrySet()) {
            if (!entry.getKey().startsWith("key") || !(entry.getValue() instanceof String)) continue;
            try {
                int code = Integer.parseInt(entry.getKey().substring(3));
                sb.append(code).append('=').append(entry.getValue()).append('\n');
            } catch (NumberFormatException ignored) {
            }
        }
        if (sb.length() > 0) writeKeys(sb.toString());
    }

    /** Called under keyApps' lock. A failed read keeps the last good cache and must never be followed by a write. */
    private static boolean readKeys(boolean force) {
        File f = keysFile();
        long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
        if (!force && stamp == keysStamp) return true;
        Map<Integer, String> loaded = new LinkedHashMap<>();
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int split = line.indexOf('=');
                    if (split <= 0) continue;
                    try {
                        loaded.put(Integer.parseInt(line.substring(0, split)), line.substring(split + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (IOException ignored) {
                return false;
            }
        }
        keyApps.clear();
        keyApps.putAll(loaded);
        keysStamp = stamp;
        return true;
    }

    /** Package the key opens ("" = leave the key to the TV). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            readKeys(false);
            String pkg = keyApps.get(keyCode);
            return pkg != null ? pkg : defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        synchronized (keyApps) {
            // Re-read before editing, including choices for buttons outside the current remote's profile.
            if (!readKeys(true)) return;
            Map<Integer, String> updated = new LinkedHashMap<>(keyApps);
            if (pkg == null) updated.remove(keyCode);
            else updated.put(keyCode, pkg);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, String> entry : updated.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            if (writeKeys(sb.toString())) {
                keysStamp = Long.MIN_VALUE;
                version++;
            }
        }
    }
}
