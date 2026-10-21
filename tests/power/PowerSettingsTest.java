package io.freemytv;

import android.content.Context;
import android.os.Build;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Contract checks run against the production PowerSettings/PowerTimers sources. */
final class PowerSettingsTest {
    private static final Context CONTEXT = new Context();
    private static final String SCREEN = "system screen_off_timeout", SLEEP = "secure sleep_timeout";
    private static final String ATTENTIVE = "secure attentive_timeout", ENABLED = "secure screensaver_enabled";
    private static int passed;

    interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        check("read never mutates", () -> {
            PowerSettings.read(CONTEXT);
            expect(Adb.writes == 0, "read wrote settings");
        });
        check("custom 19 minute interval and no-op Apply remain exact", () -> {
            Adb.values.put(SCREEN, "1140000");
            Adb.values.put(SLEEP, "1800000");
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            expect(before.saverMs == 1140000, "custom interval rounded");
            applyUnchanged(before);
            expect(Adb.writes == 0, "no-op wrote settings");
        });
        check("disabling screensaver preserves selected sleep delay", () -> {
            PowerSettings.Snapshot after = PowerSettings.apply(CONTEXT, PowerSettings.read(CONTEXT), -1, 600000, 14400000);
            expect(after.saverMs == -1 && after.sleepMs == 600000, "wrong effective timers");
            expect(Adb.values.get(SCREEN).equals("600000") && Adb.values.get(ENABLED).equals("0"), "screen-off overrides sleep");
        });
        check("all disabled timers stop short screen-off fallback", () -> {
            PowerSettings.Snapshot after = PowerSettings.apply(CONTEXT, PowerSettings.read(CONTEXT), -1, -1, -1);
            expect(after.saverMs == -1 && after.sleepMs == -1 && after.attentiveMs == -1, "timers still active");
            expect(Adb.values.get(SCREEN).equals(Integer.toString(Integer.MAX_VALUE)), "short screen-off remains");
        });
        check("invalid timer order is rejected without writes", () -> {
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            expectFailure(() -> PowerSettings.apply(CONTEXT, before, 600000, 300000, 14400000), "later");
            expect(Adb.writes == 0, "invalid timers wrote settings");
        });
        for (boolean after : new boolean[] {false, true}) {
            check("rollback after connection failure " + (after ? "after" : "before") + " write", () -> {
                PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
                Map<String, String> original = new LinkedHashMap<>(Adb.values);
                Adb.failWrite = SLEEP;
                Adb.failAfter = after;
                expectFailure(() -> PowerSettings.apply(CONTEXT, before, 360000, 900000, 14400000), "restored");
                expect(Adb.values.equals(original), "rollback changed settings");
            });
        }
        check("nonzero shell exit rolls back completed write", () -> {
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            Map<String, String> original = new LinkedHashMap<>(Adb.values);
            Adb.badExit = SCREEN;
            expectFailure(() -> PowerSettings.apply(CONTEXT, before, 360000, 900000, 14400000), "restored");
            expect(Adb.values.equals(original), "shell failure not restored");
        });
        check("external edit before Apply blocks all writes", () -> {
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            Adb.values.put(ATTENTIVE, "7200000");
            expectFailure(() -> PowerSettings.apply(CONTEXT, before, 360000, 900000, 14400000), "changed elsewhere");
            expect(Adb.writes == 0, "overwrote stale settings");
        });
        check("API 29 omits unsupported playback timer", () -> {
            Build.VERSION.SDK_INT = 29;
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            expect(!before.attentiveSupported && before.attentiveMs == -1, "exposed unsupported timer");
            expectFailure(() -> PowerSettings.apply(CONTEXT, before, 300000, 600000, 3600000), "does not support");
            expect(Adb.writes == 0, "wrote unsupported timer");
        });
        check("readback mismatch restores every completed write", () -> {
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            Map<String, String> original = new LinkedHashMap<>(Adb.values);
            Adb.mismatchAfterWrite = SLEEP;
            expectFailure(() -> PowerSettings.apply(CONTEXT, before, 360000, 900000, 14400000), "restored");
            expect(Adb.values.equals(original), "readback failure not restored");
        });
        check("rollback deletes initially absent keys", () -> {
            Adb.values.remove(ATTENTIVE);
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            Adb.failWrite = ATTENTIVE;
            Adb.failAfter = true;
            expectFailure(() -> PowerSettings.apply(CONTEXT, before, 360000, 900000, 3600000), "restored");
            expect(!Adb.values.containsKey(ATTENTIVE) && Adb.values.get(SCREEN).equals("300000"), "default key not restored");
        });
        check("rollback preserves concurrent external edit", () -> {
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            Adb.failWrite = SLEEP;
            Adb.failAfter = true;
            Adb.concurrentEdit = SCREEN;
            expectFailure(() -> PowerSettings.apply(CONTEXT, before, 360000, 900000, 14400000), "Refresh");
            expect(Adb.values.get(SCREEN).equals("1800000"), "external edit overwritten");
            expect(Adb.values.get(SLEEP).equals("600000"), "our completed write not restored");
        });
        check("enabling screensaver retains previously displayed effective sleep", () -> {
            Adb.values.put(ENABLED, "0");
            Adb.values.put(SLEEP, "-1");
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            expect(before.sleepMs == 300000, "screen-off effective sleep not shown");
            PowerSettings.Snapshot after = PowerSettings.apply(CONTEXT, before, 120000, before.sleepMs, before.attentiveMs);
            expect(after.saverMs == 120000 && after.sleepMs == 300000, "enabling screensaver lost effective sleep");
        });
        check("absent settings use firmware defaults without persisting them", () -> {
            Adb.values.clear();
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            expect(before.saverMs == 300000 && before.sleepMs == 600000 && before.attentiveMs == 14400000, "wrong firmware defaults");
            applyUnchanged(before);
            expect(Adb.writes == 0 && Adb.values.isEmpty(), "read/no-op persisted defaults");
        });
        check("firmware default false screensaver flag is honored", () -> {
            Adb.values.remove(ENABLED);
            Adb.power = Adb.power.replace("mDreamsEnabledSetting=true", "mDreamsEnabledSetting=false");
            PowerSettings.Snapshot before = PowerSettings.read(CONTEXT);
            expect(before.saverMs == -1 && before.sleepMs == 300000 && Adb.writes == 0, "ignored firmware flag");
        });
        check("missing resolved firmware field fails read without writes", () -> {
            Adb.values.remove(ATTENTIVE);
            Adb.power = Adb.power.replace("  mAttentiveTimeoutSetting=14400000\n", "");
            expectFailure(() -> PowerSettings.read(CONTEXT), "Could not read");
            expect(Adb.writes == 0, "failed read wrote settings");
        });
        check("unchanged 30 second firmware interval survives Apply", () -> {
            Adb.values.put(SCREEN, "30000");
            applyUnchanged(PowerSettings.read(CONTEXT));
            expect(Adb.writes == 0, "custom short interval rejected or overwritten");
        });
        check("shared draft validation preserves legacy timers when only playback changes", () -> {
            PowerTimers before = new PowerTimers(30000, 20000, -1);
            PowerTimers edit = before.with(PowerTimers.Kind.PLAYBACK_SLEEP, 3600000);
            expect(edit.validationError(before).isEmpty(), "untouched legacy order rejected");
            expect(!edit.with(PowerTimers.Kind.IDLE_SLEEP, 25000).validationError(before).isEmpty(), "short new timer accepted");
            expect(!edit.with(PowerTimers.Kind.SCREENSAVER, 8L * 24 * 60 * 60 * 1000).validationError(before).isEmpty(), "out-of-range timer accepted");
        });
        System.out.println("Power settings: " + passed + " checks passed");
    }

    private static void check(String name, Check action) throws Exception {
        Adb.reset();
        Build.VERSION.SDK_INT = 34;
        action.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void applyUnchanged(PowerSettings.Snapshot state) throws IOException {
        PowerSettings.apply(CONTEXT, state, state.saverMs, state.sleepMs, state.attentiveMs);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Check action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected failure: " + message);
        } catch (IOException error) {
            expect(error.getMessage().contains(message), "Unexpected error: " + error.getMessage());
        }
    }
}
