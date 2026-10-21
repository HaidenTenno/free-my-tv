package io.freemytv;

import android.content.Context;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory settings service with failures before/after writes; never opens a socket. */
final class Adb implements AutoCloseable {
    static final Map<String, String> values = new LinkedHashMap<>();
    static int writes, user;
    static String failWrite, badExit, mismatchAfterWrite, pendingMismatch, concurrentEdit, power;
    static boolean failAfter, failureUsed;

    static Adb connect(Context context, int timeout, Object approval) { return new Adb(); }
    @Override public void close() {}

    static void reset() {
        values.clear();
        writes = user = 0;
        failWrite = badExit = mismatchAfterWrite = pendingMismatch = concurrentEdit = null;
        failAfter = failureUsed = false;
        power = "Power Manager Settings and Configuration:\n"
                + "  mScreenOffTimeoutSetting=300000\n  mSleepTimeoutSetting=600000\n"
                + "  mAttentiveTimeoutSetting=14400000\n  mDreamsEnabledSetting=true\n"
                + "  mDreamsActivateOnSleepSetting=true\n";
        values.put("system screen_off_timeout", "300000");
        values.put("secure sleep_timeout", "600000");
        values.put("secure attentive_timeout", "14400000");
        values.put("secure screensaver_enabled", "1");
        values.put("secure screensaver_activate_on_sleep", "1");
    }

    String shell(String full) throws IOException {
        String command = full.substring(0, full.indexOf("; printf"));
        if (command.equals("am get-current-user")) return result(Integer.toString(user));
        if (command.equals("dumpsys power")) return result(power);
        String[] args = command.split(" ");
        if (!args[0].equals("settings")) throw new AssertionError(command);
        if (!args[2].equals(Integer.toString(user))) throw new AssertionError("Wrong settings user");
        String key = args[4] + " " + args[5];
        if (args[3].equals("get")) {
            if (key.equals(pendingMismatch)) {
                pendingMismatch = null;
                return result("999999");
            }
            return result(values.getOrDefault(key, "null"));
        }
        writes++;
        boolean fail = key.equals(failWrite) && !failureUsed;
        if (fail && !failAfter) {
            failureUsed = true;
            throw new IOException("injected before write");
        }
        if (args[3].equals("put")) values.put(key, args[6]);
        else if (args[3].equals("delete")) values.remove(key);
        else throw new AssertionError(command);
        if (fail) {
            failureUsed = true;
            if (concurrentEdit != null) values.put(concurrentEdit, "1800000");
            throw new IOException("injected after write");
        }
        if (key.equals(mismatchAfterWrite)) {
            mismatchAfterWrite = null;
            pendingMismatch = key;
        }
        if (key.equals(badExit)) {
            badExit = null;
            return "injected write error\n__FMTV_POWER_EXIT__=1\n";
        }
        return result("");
    }

    private static String result(String text) { return text + "\n__FMTV_POWER_EXIT__=0\n"; }
}
