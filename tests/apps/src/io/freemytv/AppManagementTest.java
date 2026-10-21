package io.freemytv;

import android.content.Context;
import android.content.pm.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real parser, target selection, cancellation and virtual-node behavior against small Android/ADB doubles. */
public final class AppManagementTest {
    private static int checks;
    private static final String EXIT = "\n__FMTV_APP_STOP_EXIT__=0\n";
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Checked task, String message) throws Exception {
        try { task.run(); } catch (IOException expected) { check(true, message); return; }
        throw new AssertionError(message);
    }

    private static final class Fixture {
        final Context context = new Context();
        final Map<String, Integer> uids = new LinkedHashMap<>();
        final Set<String> resident = new LinkedHashSet<>();
        final List<String> stops = new ArrayList<>();
        int user;
        String failStop;
        Runnable afterStop;

        Fixture() {
            SystemClock.now = 10_000;
            uids.put("io.freemytv", 10001);
            resident.add("io.freemytv");
            Adb.handler = this::shell;
        }
        PackageInfo add(String pkg, int uid) {
            PackageInfo pi = new PackageInfo();
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.uid = uid;
            pi.applicationInfo.label = pkg;
            context.pm.packages.put(pkg, pi);
            context.pm.launchers.add(pkg);
            uids.put(pkg, uid);
            resident.add(pkg);
            return pi;
        }
        String shell(Adb adb, String full) throws IOException {
            String command = full.substring(0, full.indexOf("; printf"));
            String result;
            if (command.equals("am get-current-user")) result = Integer.toString(user);
            else if (command.startsWith("pm list packages -U --user ")) {
                StringBuilder out = new StringBuilder();
                for (Map.Entry<String,Integer> entry : uids.entrySet())
                    out.append("package:").append(entry.getKey()).append(" uid:").append(entry.getValue()).append('\n');
                result = out.toString();
            } else if (command.equals("dumpsys activity processes")) {
                StringBuilder out = new StringBuilder();
                for (String pkg : resident) out.append("  *APP* UID ").append(uids.get(pkg))
                        .append(" ProcessRecord{abc 123:").append(pkg).append("/u0a1}\n    packageList={").append(pkg).append("}\n");
                result = out.toString();
            } else if (command.startsWith("am force-stop --user ")) {
                String pkg = command.substring(command.lastIndexOf(' ') + 1);
                stops.add(pkg);
                if (pkg.equals(failStop)) return "error\n__FMTV_APP_STOP_EXIT__=1\n";
                resident.remove(pkg);
                if (afterStop != null) afterStop.run();
                result = "";
            } else throw new AssertionError("Unexpected command: " + command);
            return result + EXIT;
        }
    }

    private static void parsers() throws Exception {
        String dump = "ACTIVITY MANAGER RUNNING PROCESSES\n"
                + "  *PERS* UID 1000 ProcessRecord{a 1:system/1000}\n    packageList={android}\n"
                + "  *APP* UID 10010 ProcessRecord{b 2:com.example.one/u0a10}\n    packageList={com.example.one, com.example.two}\n"
                + "  *APP* UID 110010 ProcessRecord{c 3:com.example.other/u1a10}\n    packageList={com.example.other}\n"
                + "Process LRU list: com.example.recent\n";
        check(AppStopper.residentPackages(dump, 0).equals(new HashSet<>(Arrays.asList("android", "com.example.one", "com.example.two"))), "resident parser filters users and LRU");
        check(AppStopper.residentPackages(dump.replace("\n", "\r\n"), 1).equals(Collections.singleton("com.example.other")), "CRLF process dump");
        rejects(() -> AppStopper.residentPackages("*APP* UID 10001 ProcessRecord{x}\n", 0), "incomplete record rejected");
        rejects(() -> AppStopper.residentPackages("*APP* UID 10001 ProcessRecord{x}\npackageList={bad;command}\n", 0), "unexpected package rejected");
        rejects(() -> AppStopper.residentPackages("recent com.example.only", 0), "LRU alone not resident proof");
        check(AppStopper.packageUids("package:android uid:1000\r\npackage:com.example.one uid:10010\n").size() == 2, "UID parser");
        rejects(() -> AppStopper.packageUids("package:com.example.one uid:NaN"), "bad UID rejected");
        rejects(() -> AppStopper.packageUids("unexpected stderr"), "unknown package list output rejected");
        check(AppStopper.checkedOutput("value\r\n__FMTV_APP_STOP_EXIT__=0\r\n").equals("value"), "successful shell output");
        rejects(() -> AppStopper.checkedOutput("value"), "truncated shell output rejected");
        rejects(() -> AppStopper.checkedOutput("failure\n__FMTV_APP_STOP_EXIT__=1"), "shell exit error rejected");
        check(!AppStopper.validPackage("com.example;reboot") && !AppStopper.validPackage("com.example\n"), "package injection rejected");
    }

    private static void safetyAndRevalidation() throws Exception {
        Fixture f = new Fixture();
        f.add("com.example.normal", 10010);
        f.add("com.example.vpn", 10011).services = new ServiceInfo[] {new ServiceInfo("android.permission.BIND_VPN_SERVICE")};
        f.add("com.example.shared", 10012); f.uids.put("com.example.shared.peer", 10012);
        f.add("com.example.home", 10013); f.context.pm.homes.add("com.example.home");
        f.add("com.example.accessibility", 10014).services = new ServiceInfo[] {new ServiceInfo("android.permission.BIND_ACCESSIBILITY_SERVICE")};
        f.add("com.tcl.system", 10015).applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        f.add("com.example.persistent", 10016).applicationInfo.flags = ApplicationInfo.FLAG_PERSISTENT;
        f.add("com.example.disabled", 10017).applicationInfo.enabled = false;
        f.add("com.android.vending", 10018);
        f.add("com.example.systemuid", 1000);
        f.add("com.example.wronguser", 110019);
        AppStopper run = new AppStopper(f.context);
        List<AppStopper.App> preview = run.prepare();
        check(preview.size() == 2, "protected/home/shared/system apps excluded");
        check(preview.get(1).vpn, "VPN retained with warning flag");
        f.context.pm.reads.clear(); f.context.pm.globalQueries = 0;
        AppStopper.Result result = run.stop(preview);
        check(result.stopped.size() == 2 && result.failed.isEmpty(), "confirmed apps stopped");
        check(f.context.pm.reads.size() == 2 && f.context.pm.reads.values().stream().allMatch(n -> n == 1), "only target services are reloaded per stop");
        check(f.context.pm.globalQueries == 0, "stop never repeats global launcher/home catalogue");
        AppStopper usedRun = run; List<AppStopper.App> usedPreview = preview;
        rejects(() -> usedRun.stop(usedPreview), "one stop per preview");

        f = new Fixture(); f.add("com.example.one", 10010);
        run = new AppStopper(f.context); preview = run.prepare();
        f.uids.put("com.example.newshared", 10010);
        result = run.stop(preview);
        check(result.failed.size() == 1 && f.stops.isEmpty(), "new shared UID after preview blocks stop");

        for (String change : Arrays.asList("home", "service", "launcher", "uid", "uninstall")) {
            f = new Fixture(); PackageInfo app = f.add("com.example.one", 10010);
            run = new AppStopper(f.context); preview = run.prepare();
            switch (change) {
                case "home": f.context.pm.homes.add("com.example.one"); break;
                case "service": app.services = new ServiceInfo[]{new ServiceInfo("android.permission.BIND_TV_INPUT")}; break;
                case "launcher": f.context.pm.launchers.clear(); break;
                case "uid": f.uids.put("com.example.one", 10020); break;
                case "uninstall": f.context.pm.packages.clear(); break;
            }
            result = run.stop(preview);
            check(result.failed.size() == 1 && f.stops.isEmpty(), "target change rechecked: " + change);
        }
        Fixture switched = new Fixture(); switched.add("com.example.one", 10010);
        AppStopper switchedRun = new AppStopper(switched.context); List<AppStopper.App> switchedPreview = switchedRun.prepare();
        switched.user = 1;
        rejects(() -> switchedRun.stop(switchedPreview), "user switch rejected");
        check(switched.stops.isEmpty(), "no command after user switch");

        Fixture forged = new Fixture(); forged.add("com.example.one", 10010);
        AppStopper forgedRun = new AppStopper(forged.context); forgedRun.prepare();
        rejects(() -> forgedRun.stop(Collections.singletonList(new AppStopper.App("com.example.unconfirmed", "Other", false))), "unconfirmed app rejected");
        check(forged.stops.isEmpty(), "no unconfirmed stops");
    }

    private static void cancellationAndFailure() throws Exception {
        Fixture f = new Fixture(); f.add("com.example.one", 10010); f.add("com.example.two", 10011);
        AppStopper run = new AppStopper(f.context); List<AppStopper.App> preview = run.prepare();
        f.failStop = "com.example.one";
        AppStopper.Result result = run.stop(preview);
        check(result.failed.size() == 2 && result.stopped.isEmpty() && f.stops.size() == 1, "shell failure stops remaining commands");

        Fixture partial = new Fixture(); partial.add("com.example.one", 10010); partial.add("com.example.two", 10011);
        AppStopper partialRun = new AppStopper(partial.context); List<AppStopper.App> partialPreview = partialRun.prepare();
        partial.afterStop = partialRun::cancel;
        rejects(() -> partialRun.stop(partialPreview), "cancel after first stop aborts operation");
        check(partial.stops.equals(Collections.singletonList("com.example.one")), "no second stop after cancellation");

        Fixture before = new Fixture(); before.add("com.example.one", 10010);
        AppStopper beforeRun = new AppStopper(before.context); List<AppStopper.App> beforePreview = beforeRun.prepare(); beforeRun.cancel();
        rejects(() -> beforeRun.stop(beforePreview), "cancel is terminal between preview and stop");
        check(before.stops.isEmpty(), "no stops after cancellation");

        Fixture blocked = new Fixture(); blocked.add("com.example.one", 10010);
        AppStopper blockedRun = new AppStopper(blocked.context);
        CountDownLatch reading = new CountDownLatch(1);
        Adb.handler = (adb, command) -> {
            reading.countDown();
            synchronized (adb) {
                while (!adb.closed) {
                    try { adb.wait(2000); } catch (InterruptedException e) { throw new IOException(e); }
                }
            }
            throw new IOException("connection closed");
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {try {blockedRun.prepare();}catch(Throwable e){thrown.set(e);}});
        worker.start(); check(reading.await(2, TimeUnit.SECONDS), "entered blocked ADB read"); blockedRun.cancel(); worker.join(2000);
        check(!worker.isAlive() && thrown.get() instanceof IOException && Adb.latest.closed, "cancel closes connection and unblocks worker");

        Fixture timeout = new Fixture(); timeout.add("com.example.one", 10010);
        AppStopper timeoutRun = new AppStopper(timeout.context); List<AppStopper.App> timeoutPreview = timeoutRun.prepare();
        Adb.Handler real = Adb.handler;
        Adb.handler = (adb, command) -> {SystemClock.now += 46_000;return real.shell(adb,command);};
        rejects(() -> timeoutRun.stop(timeoutPreview), "deadline aborts slow command sequence");
        check(timeout.stops.isEmpty(), "deadline prevents later force-stop");
    }

    private static final class Source implements Spoken.Source {
        final List<Integer> ids = new ArrayList<>(Arrays.asList(1, 2));
        int focus = 1, clicks, holds;
        public void items(List<Integer> out){out.addAll(ids);}
        public String text(int id){return "Node " + id;}
        public boolean bounds(int id, Rect out){out.set(0,0,100,100);return true;}
        public int focused(){return focus;}
        public void focus(int id){focus=id;}
        public void click(int id){clicks++;}
        public boolean longClickable(int id){return id == 1;}
        public void longClick(int id){holds++;}
    }
    private static void accessibility() {
        SystemClock.now = 10_000;
        Source source = new Source(); Spoken spoken = new Spoken(new View(new Context()), source);
        AccessibilityNodeInfo app = spoken.createAccessibilityNodeInfo(1), shortcut = spoken.createAccessibilityNodeInfo(2);
        check(app.longClickable && app.actions.contains(AccessibilityNodeInfo.ACTION_LONG_CLICK), "app advertises long click");
        check(!shortcut.longClickable && !shortcut.actions.contains(AccessibilityNodeInfo.ACTION_LONG_CLICK), "shortcut has no management action");
        check(!spoken.performAction(2, AccessibilityNodeInfo.ACTION_LONG_CLICK, null), "shortcut long click rejected");
        check(!spoken.performAction(99, AccessibilityNodeInfo.ACTION_CLICK, null)
                && !spoken.performAction(99, AccessibilityNodeInfo.ACTION_LONG_CLICK, null)
                && spoken.createAccessibilityNodeInfo(99) == null, "invalid nodes cannot open apps or management");
        check(spoken.performAction(1, AccessibilityNodeInfo.ACTION_LONG_CLICK, null) && source.holds == 1 && source.clicks == 0, "long click manages without launching");
        check(!spoken.keyClick(), "release after accessibility hold does not launch");
        spoken.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null);
        check(source.clicks == 0, "duplicate accessibility click after hold suppressed");
        check(!spoken.keyLongClick() && source.holds == 1, "physical duplicate hold suppressed");
        SystemClock.now += 1000;
        check(spoken.keyLongClick(), "normal physical hold allowed");
        spoken.performAction(1, AccessibilityNodeInfo.ACTION_LONG_CLICK, null);
        check(source.holds == 1, "duplicate accessibility long click after physical hold suppressed");
        source.ids.remove(Integer.valueOf(1)); SystemClock.now += 1000;
        check(!spoken.performAction(1, AccessibilityNodeInfo.ACTION_LONG_CLICK, null), "removed app node rejected");
        check(spoken.performAction(2, AccessibilityNodeInfo.ACTION_CLICK, null) && source.clicks == 1, "normal accessible click still works");
    }

    public static void main(String[] args) throws Exception {
        parsers(); safetyAndRevalidation(); cancellationAndFailure(); accessibility();
        System.out.println("App management: " + checks + " checks passed");
    }
}
