package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the TV's actual timers; writes only after Apply. All methods run on a worker thread. */
final class PowerSettings {
    private static final String SCREEN = "system screen_off_timeout";
    private static final String SLEEP = "secure sleep_timeout";
    private static final String ATTENTIVE = "secure attentive_timeout";
    private static final String ENABLED = "secure screensaver_enabled";
    private static final String ON_SLEEP = "secure screensaver_activate_on_sleep";
    private static final String EXIT = "__FMTV_POWER_EXIT__=";
    private static final Object LOCK = new Object();

    static final class Snapshot {
        final long saverMs, sleepMs, attentiveMs;
        final boolean attentiveSupported;
        private final int user;
        private final Map<String, String> raw;

        PowerTimers timers() { return new PowerTimers(saverMs, sleepMs, attentiveMs); }

        private Snapshot(int user, Map<String, String> raw, Map<String, String> effective) throws IOException {
            this.user = user;
            this.raw = raw;
            attentiveSupported = raw.containsKey(ATTENTIVE);
            boolean saver = number(effective.get(ENABLED)) != 0 && number(effective.get(ON_SLEEP)) != 0;
            long screen = number(effective.get(SCREEN));
            saverMs = saver ? screen : -1;
            long sleep = optionalTimer(number(effective.get(SLEEP)));
            // With no dream, screen-off itself sleeps the TV. Show the effective delay,
            // not an unused later sleep timeout (or an incorrect "Never").
            if (!saver && screen > 0 && screen < Integer.MAX_VALUE)
                sleep = sleep > 0 ? Math.min(sleep, screen) : screen;
            sleepMs = sleep;
            attentiveMs = attentiveSupported ? optionalTimer(number(effective.get(ATTENTIVE))) : -1;
        }
    }

    private PowerSettings() {}

    static Snapshot read(Context c) throws IOException {
        synchronized (LOCK) {
            try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
                return read(adb, deadline());
            }
        }
    }

    static Snapshot apply(Context c, Snapshot expected, long saverMs, long sleepMs, long attentiveMs) throws IOException {
        String validation = new PowerTimers(saverMs, sleepMs, attentiveMs).validationError(expected == null ? null : expected.timers());
        if (!validation.isEmpty()) throw new IOException(validation);
        synchronized (LOCK) {
            try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
                long limit = deadline();
                Snapshot before = read(adb, limit);
                if (before.user != expected.user || !before.raw.equals(expected.raw))
                    throw new IOException("Settings changed elsewhere. Refresh and try again.");
                if (!before.attentiveSupported && attentiveMs != before.attentiveMs)
                    throw new IOException("This TV does not support the playback sleep timer.");

                Map<String, String> wanted = new LinkedHashMap<>(before.raw);
                if (saverMs != before.saverMs || sleepMs != before.sleepMs) {
                    // Without a dream, screen-off is sleep. Keep the requested sleep delay instead
                    // of leaving an earlier screen-off timeout that would put the TV to sleep first.
                    long screen = saverMs > 0 ? saverMs : sleepMs > 0 ? sleepMs : Integer.MAX_VALUE;
                    wanted.put(SCREEN, Long.toString(screen));
                    if (saverMs != before.saverMs) {
                        wanted.put(ENABLED, saverMs > 0 ? "1" : "0");
                        if (saverMs > 0) wanted.put(ON_SLEEP, "1");
                    }
                    // Enabling a dream changes which timeout governs sleep: an unchanged displayed
                    // effective delay must also become the real sleep_timeout in the new mode.
                    wanted.put(SLEEP, Long.toString(sleepMs));
                }
                if (before.attentiveSupported && attentiveMs != before.attentiveMs)
                    wanted.put(ATTENTIVE, Long.toString(attentiveMs));

                Map<String, String> changed = new LinkedHashMap<>();
                try {
                    for (Map.Entry<String, String> entry : wanted.entrySet()) {
                        String key = entry.getKey(), value = entry.getValue();
                        if (value.equals(before.raw.get(key))) continue;
                        // Include a possibly completed write if the connection fails before its reply.
                        changed.put(key, value);
                        write(adb, before.user, key, value, limit);
                    }
                    Snapshot after = read(adb, limit);
                    if (after.user != before.user || !after.raw.equals(wanted))
                        throw new IOException("The TV did not keep the requested settings.");
                    return after;
                } catch (IOException | RuntimeException failure) {
                    boolean restored = rollback(c, before, changed);
                    AppLog.w("power settings update failed; restored=" + restored);
                    throw new IOException(restored ? "Could not apply settings. Previous values were restored."
                            : "Could not apply all settings. Refresh to check the TV's current values.", failure);
                }
            }
        }
    }

    private static Snapshot read(Adb adb, long limit) throws IOException {
        int user = user(adb, limit);
        Map<String, String> raw = new LinkedHashMap<>();
        for (String key : new String[] {SCREEN, SLEEP, ENABLED, ON_SLEEP}) raw.put(key, get(adb, user, key, limit));
        if (Build.VERSION.SDK_INT >= 30) raw.put(ATTENTIVE, get(adb, user, ATTENTIVE, limit));
        Map<String, String> effective = new LinkedHashMap<>(raw);
        if (raw.containsValue("null")) {
            // Missing database entries use firmware resource defaults. Read the power service's
            // resolved values instead of guessing, while retaining null for conflict checks/rollback.
            String power = shell(adb, "dumpsys power", limit);
            String[][] fields = {{SCREEN, "mScreenOffTimeoutSetting"}, {SLEEP, "mSleepTimeoutSetting"},
                    {ATTENTIVE, "mAttentiveTimeoutSetting"}, {ENABLED, "mDreamsEnabledSetting"},
                    {ON_SLEEP, "mDreamsActivateOnSleepSetting"}};
            for (String[] field : fields) {
                if (!"null".equals(raw.get(field[0]))) continue;
                Matcher match = Pattern.compile("(?m)^\\s*" + field[1] + "=(true|false|-?\\d+)\\s*$").matcher(power);
                if (!match.find()) throw new IOException("Could not read the TV's timer settings.");
                String value = match.group(1);
                effective.put(field[0], value.equals("true") ? "1" : value.equals("false") ? "0" : value);
            }
        }
        if (user(adb, limit) != user) throw new IOException("The active TV user changed.");
        return new Snapshot(user, raw, effective);
    }

    private static String get(Adb adb, int user, String key, long limit) throws IOException {
        String value = shell(adb, "settings --user " + user + " get " + key, limit);
        if (!value.equals("null") && !value.matches("-?\\d+"))
            throw new IOException("Could not read the TV's timer settings.");
        return value;
    }

    private static void write(Adb adb, int user, String key, String value, long limit) throws IOException {
        if (user(adb, limit) != user) throw new IOException("The active TV user changed.");
        shell(adb, "settings --user " + user + (value.equals("null") ? " delete " : " put ")
                + key + (value.equals("null") ? "" : " " + value), limit);
        if (!get(adb, user, key, limit).equals(value))
            throw new IOException("The TV did not keep the requested settings.");
    }

    private static boolean rollback(Context c, Snapshot before, Map<String, String> changed) {
        if (changed.isEmpty()) return true;
        try (Adb adb = Adb.connect(c.getApplicationContext(), 10_000, null)) {
            long limit = deadline();
            if (user(adb, limit) != before.user) return false;
            boolean complete = true;
            for (Map.Entry<String, String> entry : changed.entrySet()) {
                String key = entry.getKey(), current = get(adb, before.user, key, limit);
                if (current.equals(before.raw.get(key))) continue;
                // Do not overwrite a concurrent edit made through Android's own settings.
                if (!current.equals(entry.getValue())) { complete = false; continue; }
                write(adb, before.user, key, before.raw.get(key), limit);
            }
            return complete;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static int user(Adb adb, long limit) throws IOException {
        String value = shell(adb, "am get-current-user", limit);
        if (!value.matches("\\d{1,5}")) throw new IOException("Could not identify the active TV user.");
        return Integer.parseInt(value);
    }

    private static long number(String raw) throws IOException {
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { throw new IOException("Could not read the TV's timer settings.", e); }
    }

    private static long optionalTimer(long value) { return value <= 0 ? -1 : value; }
    private static long deadline() { return SystemClock.elapsedRealtime() + 45_000; }

    private static String shell(Adb adb, String command, long limit) throws IOException {
        if (SystemClock.elapsedRealtime() > limit) throw new IOException("Reading or saving settings timed out.");
        String out = adb.shell(command + "; printf '\\n" + EXIT + "%s\\n' \"$?\"")
                .replace("\r\n", "\n").trim();
        int marker = out.lastIndexOf("\n" + EXIT);
        if (marker < 0 && out.startsWith(EXIT)) marker = 0;
        if (marker < 0 || !(EXIT + "0").equals(out.substring(marker).trim()))
            throw new IOException("The TV could not complete the settings command.");
        return out.substring(0, marker).trim();
    }
}
