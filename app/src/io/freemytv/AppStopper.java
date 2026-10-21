package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stops only explicitly confirmed, resident TV apps. Call prepare/stop on a worker thread. */
final class AppStopper {
    static final class App {
        final String pkg, label;
        final boolean vpn;

        App(String pkg, String label, boolean vpn) {
            this.pkg = pkg;
            this.label = label;
            this.vpn = vpn;
        }
    }

    static final class Result {
        final List<App> stopped, failed;

        Result(List<App> stopped, List<App> failed) {
            this.stopped = Collections.unmodifiableList(new ArrayList<>(stopped));
            this.failed = Collections.unmodifiableList(new ArrayList<>(failed));
        }
    }

    private static final long TIMEOUT_MS = 45_000;
    private static final String EXIT = "__FMTV_APP_STOP_EXIT__=";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern PROCESS = Pattern.compile("^\\s*\\*(APP|PERS)\\* UID (\\d+) ProcessRecord\\{.*$");
    private static final Pattern PACKAGES = Pattern.compile("^\\s*packageList=\\{([^}]*)\\}\\s*$");
    private static final Pattern UID = Pattern.compile("^package:([A-Za-z0-9_.]+) uid:(\\d+)$");
    // Manufacturer utilities often have TV launcher icons, but control inputs, updates or system UI.
    private static final String[] OEM_SYSTEM = {
        "com.tcl.", "com.haier.", "com.haierubic.", "com.mediatek.", "mediatek.",
        "com.homwee.", "com.cvte.", "com.express.", "com.expressluck.", "com.at.factory.",
        "org.droidtv.", "com.mstar.", "com.mitv.", "com.xiaomi.", "mitv."
    };

    private final Context context;
    private final Object lock = new Object();
    private boolean cancelled, running, timedOut, stopStarted;
    private long deadline, generation;
    private Adb connection;
    private int preparedUser = -1;
    private Map<String, App> prepared = Collections.emptyMap();

    AppStopper(Context context) {
        this.context = context.getApplicationContext();
    }

    /** No connection or deadline remains active while the user reads the confirmation. */
    List<App> prepare() throws IOException {
        begin();
        try {
            Adb adb = connect();
            int user = currentUser(adb);
            Map<String, App> eligible = eligible(adb, user);
            Set<String> resident = residentPackages(shell(adb, "dumpsys activity processes"), user);
            List<App> apps = new ArrayList<>();
            for (App app : eligible.values()) if (resident.contains(app.pkg)) apps.add(app);
            Collections.sort(apps, Comparator.comparing(a -> a.label, String.CASE_INSENSITIVE_ORDER));
            check();
            prepared = new LinkedHashMap<>();
            for (App app : apps) prepared.put(app.pkg, app);
            preparedUser = user;
            return Collections.unmodifiableList(apps);
        } finally {
            end();
        }
    }

    /** Only packages from this instance's preview can be stopped; every success is checked afterward. */
    Result stop(List<App> confirmed) throws IOException {
        begin();
        try {
            if (preparedUser < 0) throw new IOException("Prepare the application list first");
            if (stopStarted) throw new IOException("Application stopping was already requested");
            stopStarted = true;
            Adb adb = connect();
            int user = currentUser(adb);
            if (user != preparedUser) throw new IOException("The active TV user changed");
            Map<String, App> eligible = eligible(adb, user);
            Map<String, App> selected = new LinkedHashMap<>();
            if (confirmed != null) for (App app : confirmed) {
                if (app == null || !validPackage(app.pkg) || !prepared.containsKey(app.pkg))
                    throw new IOException("The confirmed application list changed");
                selected.put(app.pkg, prepared.get(app.pkg));
            }
            List<App> stopped = new ArrayList<>(), failed = new ArrayList<>();
            boolean broken = false;
            for (App app : selected.values()) {
                check();
                if (broken || !eligible.containsKey(app.pkg)) {
                    failed.add(app);
                    continue;
                }
                try {
                    // A user switch or package replacement must not broaden the confirmed operation.
                    if (currentUser(adb) != user) throw new IOException("The active TV user changed");
                    if (!eligible(adb, user).containsKey(app.pkg)) {
                        failed.add(app);
                        continue;
                    }
                    shell(adb, "am force-stop --user " + user + " " + app.pkg);
                    Set<String> resident = residentPackages(shell(adb, "dumpsys activity processes"), user);
                    if (resident.contains(app.pkg)) failed.add(app);
                    else stopped.add(app);
                } catch (IOException e) {
                    check(); // Cancellation and the operation deadline must abort the remaining commands.
                    failed.add(app);
                    // An unsuccessful shell command may leave its stream unusable. Do not issue more stops.
                    broken = true;
                }
            }
            check();
            return new Result(stopped, failed);
        } finally {
            end();
        }
    }

    /** Terminal for this instance. Safe from the UI thread, including while ADB is reading. */
    void cancel() {
        Adb adb;
        synchronized (lock) {
            cancelled = true;
            adb = connection;
            lock.notifyAll();
        }
        if (adb != null) adb.close();
    }

    private Map<String, App> eligible(Adb adb, int user) throws IOException {
        Map<String, Integer> uids = packageUids(shell(adb, "pm list packages -U --user " + user));
        Integer ownUid = uids.get(context.getPackageName());
        if (ownUid == null || ownUid != context.getApplicationInfo().uid)
            throw new IOException("Cannot verify packages for the active TV user");
        Map<Integer, Integer> uidCounts = new HashMap<>();
        for (int uid : uids.values()) uidCounts.put(uid, uidCounts.getOrDefault(uid, 0) + 1);
        PackageManager pm = context.getPackageManager();
        Set<String> homes = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (ri.activityInfo != null) homes.add(ri.activityInfo.packageName);
        }
        Map<String, App> result = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            check();
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (!validPackage(pkg) || context.getPackageName().equals(pkg) || homes.contains(pkg)
                    || HomeMode.isProtectedPackage(pkg)) continue;
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS);
                ApplicationInfo ai = pi.applicationInfo;
                Integer uid = uids.get(pkg);
                if (ai == null || !ai.enabled || uid == null || uid != ai.uid || uid / 100000 != user
                        || uid % 100000 < 10000 || uidCounts.get(uid) != 1
                        || (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                        && oemSystemPackage(pkg)) continue;
                boolean protectedService = false;
                boolean vpn = "com.vanyavpn.android.client".equals(pkg);
                if (pi.services != null) for (ServiceInfo service : pi.services) {
                    String permission = service.permission;
                    if ("android.permission.BIND_VPN_SERVICE".equals(permission)) vpn = true;
                    if ("android.permission.BIND_TV_INPUT".equals(permission)
                            || "android.permission.BIND_INPUT_METHOD".equals(permission)
                            || "android.permission.BIND_ACCESSIBILITY_SERVICE".equals(permission)
                            || "android.permission.BIND_DREAM_SERVICE".equals(permission)
                            || "android.permission.BIND_WALLPAPER".equals(permission)) protectedService = true;
                }
                if (protectedService) continue;
                CharSequence label = ai.loadLabel(pm);
                result.put(pkg, new App(pkg, label == null ? pkg : label.toString(), vpn));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled while collecting the preview.
            }
        }
        return result;
    }

    private static boolean oemSystemPackage(String pkg) {
        for (String prefix : OEM_SYSTEM) if (pkg.startsWith(prefix)) return true;
        return "org.droidtv".equals(pkg) || "com.google.android.tv".equals(pkg)
                || "com.sony.dtv.tvx".equals(pkg);
    }

    static boolean validPackage(String pkg) {
        return pkg != null && PACKAGE.matcher(pkg).matches();
    }

    /** Only the actual running-record section has these headers; recents/LRU names are not proof of residency. */
    static Set<String> residentPackages(String dump, int user) throws IOException {
        Set<String> result = new HashSet<>();
        int uid = -1, records = 0, lists = 0;
        for (String line : dump.split("\\r?\\n")) {
            Matcher header = PROCESS.matcher(line);
            if (header.matches()) {
                if (uid != -1) throw new IOException("Incomplete running-process record");
                uid = integer(header.group(2));
                records++;
                continue;
            }
            Matcher packages = PACKAGES.matcher(line);
            if (uid >= 0 && packages.matches()) {
                for (String pkg : packages.group(1).split(",")) {
                    pkg = pkg.trim();
                    if (pkg.isEmpty()) continue;
                    if (!validPackage(pkg) && !"android".equals(pkg))
                        throw new IOException("Unexpected running-process package");
                    if (uid / 100000 == user) result.add(pkg);
                }
                lists++;
                uid = -1;
            }
        }
        if (records == 0 || records != lists) throw new IOException("Cannot read running TV applications");
        return result;
    }

    static Map<String, Integer> packageUids(String dump) throws IOException {
        Map<String, Integer> result = new HashMap<>();
        for (String line : dump.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            Matcher match = UID.matcher(line.trim());
            if (!match.matches()) throw new IOException("Cannot verify installed application UIDs");
            String pkg = match.group(1);
            if (!validPackage(pkg) && !"android".equals(pkg)) throw new IOException("Invalid installed package");
            result.put(pkg, integer(match.group(2)));
        }
        if (result.isEmpty()) throw new IOException("Installed application list is empty");
        return result;
    }

    private static int integer(String value) throws IOException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IOException("Invalid TV user or UID", e); }
    }

    private int currentUser(Adb adb) throws IOException {
        String value = shell(adb, "am get-current-user").trim();
        if (!value.matches("\\d+")) throw new IOException("Cannot determine the active TV user");
        return integer(value);
    }

    private String shell(Adb adb, String command) throws IOException {
        check();
        String output = adb.shell(command + "; printf '\\n" + EXIT + "%s\\n' \"$?\"");
        check();
        return checkedOutput(output);
    }

    static String checkedOutput(String output) throws IOException {
        String normalized = output.replace("\r\n", "\n").trim();
        int marker = normalized.lastIndexOf("\n" + EXIT);
        if (marker < 0 && normalized.startsWith(EXIT)) marker = 0;
        if (marker < 0) throw new IOException("ADB did not confirm command completion");
        String status = normalized.substring(marker).trim();
        if (!(EXIT + "0").equals(status)) throw new IOException("The TV could not complete the command");
        return normalized.substring(0, marker).trim();
    }

    private Adb connect() throws IOException {
        check();
        Adb adb = Adb.connect(context, 10_000, null);
        synchronized (lock) {
            try {
                check();
                connection = adb;
            } catch (IOException e) {
                adb.close();
                throw e;
            }
        }
        return adb;
    }

    private void begin() throws IOException {
        final long token;
        synchronized (lock) {
            if (cancelled) throw new IOException("Application stopping was cancelled");
            if (running) throw new IOException("Application stopping is already running");
            running = true;
            timedOut = false;
            deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            token = ++generation;
        }
        Thread watchdog = new Thread(() -> {
            Adb adb = null;
            synchronized (lock) {
                while (running && generation == token && !cancelled) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) {
                        timedOut = true;
                        adb = connection;
                        break;
                    }
                    try { lock.wait(remaining); }
                    catch (InterruptedException e) { return; }
                }
            }
            if (adb != null) adb.close();
        }, "app-stop-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void check() throws IOException {
        synchronized (lock) {
            if (cancelled) throw new IOException("Application stopping was cancelled");
            if (timedOut || (running && SystemClock.elapsedRealtime() >= deadline))
                throw new IOException("Application stopping timed out");
        }
    }

    private void end() {
        Adb adb;
        synchronized (lock) {
            adb = connection;
            connection = null;
            running = false;
            lock.notifyAll();
        }
        if (adb != null) adb.close();
    }
}
