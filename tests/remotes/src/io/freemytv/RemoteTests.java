package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Exercises the production key handler and resolver with delayed Android API calls. */
public final class RemoteTests {
    private static Context context;
    private static int checks;

    public static void main(String[] args) throws Exception {
        context = new Context(Files.createTempDirectory(Path.of(args[0]), "keys-").toFile());
        Prefs.init(context);
        coldProviderAndPressPair();
        boundedLaunchQueue();
        systemActionSupersedesSlowLaunch();
        navigationCancelsButVolumeDoesNot();
        systemActionAndOtherInputs();
        failingLookupDoesNotStopWorker();
        System.out.println("remotes: " + checks + " checks passed");
    }

    private static void coldProviderAndPressPair() throws Exception {
        Prefs.setKeyApp(4001, LiveTv.TARGET);
        ContentResolver.entered = new CountDownLatch(1);
        ContentResolver.release = new CountDownLatch(1);
        long started = System.nanoTime();
        check(key(KeyEvent.ACTION_DOWN, 4001, 0, 1), "mapped DOWN must be consumed");
        check((System.nanoTime() - started) / 1_000_000 < 400, "key callback waits for TV provider");
        check(ContentResolver.entered.await(1, TimeUnit.SECONDS), "worker did not query TV provider");
        Prefs.setKeyApp(4001, "");
        for (int repeat = 1; repeat <= 30; repeat++)
            check(key(KeyEvent.ACTION_DOWN, 4001, repeat, 1), "repeat lost its original mapping");
        check(key(KeyEvent.ACTION_UP, 4001, 0, 1), "UP must follow mapped DOWN after preference change");
        check(context.launches.isEmpty(), "provider is blocked: no launch should have occurred");
        ContentResolver.release.countDown();
        await(() -> context.launches.size() == 1, "TV target was not launched");
        check(ContentResolver.queries.get() == 1, "held key queued repeated TV requests");
        ContentResolver.entered = ContentResolver.release = null;
    }

    private static void boundedLaunchQueue() throws Exception {
        int launches = context.launches.size();
        int lookups = PackageManager.appLookups.get();
        PackageManager.entered = new CountDownLatch(1);
        PackageManager.release = new CountDownLatch(1);
        Prefs.setKeyApp(4143, "app.blocked");
        long started = System.nanoTime();
        press(4143);
        check((System.nanoTime() - started) / 1_000_000 < 400, "key callback waits for PackageManager");
        check(PackageManager.entered.await(1, TimeUnit.SECONDS), "worker did not resolve app");
        for (int i = 0; i < 100; i++) {
            Prefs.setKeyApp(4143, "app." + i);
            press(4143);
        }
        check(context.launches.size() == launches, "blocked app launched prematurely");
        PackageManager.release.countDown();
        await(() -> context.launches.size() == launches + 1, "latest pending target was not launched");
        check("app.99".equals(context.launches.get(launches).target), "stale target launched after lookup");
        check(PackageManager.appLookups.get() - lookups == 2, "pending requests formed an unbounded backlog");
        PackageManager.entered = PackageManager.release = null;
    }

    private static void systemActionAndOtherInputs() throws Exception {
        int before = context.launches.size();
        Prefs.setKeyApp(4143, "");
        check(!key(KeyEvent.ACTION_DOWN, 4143, 0, 1), "system action DOWN was consumed");
        Prefs.setKeyApp(4143, "app.assigned-while-held");
        check(!key(KeyEvent.ACTION_DOWN, 4143, 1, 1), "system action repeat changed ownership");
        check(!key(KeyEvent.ACTION_UP, 4143, 0, 1), "system action UP changed ownership");
        check(!key(KeyEvent.ACTION_UP, 4143, 0, 1), "unpaired UP was consumed");
        check(!key(KeyEvent.ACTION_DOWN, 20, 0, 1), "navigation key was consumed");
        check(!key(KeyEvent.ACTION_MULTIPLE, 4143, 0, 1), "ACTION_MULTIPLE was consumed");
        check(!RemoteKeys.onKey(context, new KeyEvent(KeyEvent.ACTION_DOWN, 4143, 0, 1,
                InputDevice.SOURCE_GAMEPAD)), "gamepad key was consumed");
        check(context.launches.size() == before, "system action unexpectedly launched an app");

        // Two input devices can hold the same code without changing each other's DOWN/UP decision.
        Prefs.setKeyApp(4143, "device.one");
        check(key(KeyEvent.ACTION_DOWN, 4143, 0, 1), "first device DOWN was not consumed");
        Prefs.setKeyApp(4143, "");
        check(!key(KeyEvent.ACTION_DOWN, 4143, 0, 2), "second device DOWN was consumed");
        check(key(KeyEvent.ACTION_UP, 4143, 0, 1), "second device overwrote first device press");
        check(!key(KeyEvent.ACTION_UP, 4143, 0, 2), "first device overwrote second device press");
        await(() -> context.launches.size() == before + 1, "first device launch missing");
    }

    private static void systemActionSupersedesSlowLaunch() throws Exception {
        int launches = context.launches.size();
        int lookups = PackageManager.appLookups.get();
        Prefs.setKeyApp(4001, LiveTv.TARGET);
        ContentResolver.entered = new CountDownLatch(1);
        ContentResolver.release = new CountDownLatch(1);
        press(4001);
        check(ContentResolver.entered.await(1, TimeUnit.SECONDS), "TV lookup did not start");
        Prefs.setKeyApp(4143, "queued.app");
        press(4143);
        Prefs.setKeyApp(4100, "");
        check(!key(KeyEvent.ACTION_DOWN, 4100, 0, 1), "native action DOWN must pass through");
        check(!key(KeyEvent.ACTION_UP, 4100, 0, 1), "native action UP must pass through");
        ContentResolver.release.countDown();
        awaitIdle();
        check(context.launches.size() == launches, "stale launch would replace the native app");
        check(PackageManager.appLookups.get() == lookups, "cancelled pending app was still resolved");
        ContentResolver.entered = ContentResolver.release = null;
    }

    private static void navigationCancelsButVolumeDoesNot() throws Exception {
        for (int code : new int[] {KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP}) {
            int launches = context.launches.size();
            ContentResolver.entered = new CountDownLatch(1);
            ContentResolver.release = new CountDownLatch(1);
            press(4001);
            check(ContentResolver.entered.await(1, TimeUnit.SECONDS), "TV lookup did not start");
            check(!key(KeyEvent.ACTION_DOWN, code, 0, 1), "navigation/volume DOWN was consumed");
            check(!key(KeyEvent.ACTION_UP, code, 0, 1), "navigation/volume UP was consumed");
            ContentResolver.release.countDown();
            awaitIdle();
            int expected = code == KeyEvent.KEYCODE_VOLUME_UP ? launches + 1 : launches;
            check(context.launches.size() == expected, "incorrect cancellation for key " + code);
            ContentResolver.entered = ContentResolver.release = null;
        }
    }

    private static void awaitIdle() throws Exception {
        await(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.getName().equals("remote-launch") && thread.isAlive()),
                "cancelled worker did not finish");
    }

    private static void failingLookupDoesNotStopWorker() throws Exception {
        int lookups = PackageManager.appLookups.get();
        int launches = context.launches.size();
        Prefs.setKeyApp(4143, "broken.app");
        press(4143);
        await(() -> PackageManager.appLookups.get() > lookups, "broken package was not tried");
        Prefs.setKeyApp(4143, "good.app");
        press(4143);
        await(() -> context.launches.size() == launches + 1, "worker stopped after package exception");
        check("good.app".equals(context.launches.get(launches).target), "wrong recovery target");
    }

    private static boolean key(int action, int code, int repeat, int device) {
        return RemoteKeys.onKey(context, new KeyEvent(action, code, repeat, device, 0x101));
    }

    private static void press(int code) {
        check(key(KeyEvent.ACTION_DOWN, code, 0, 1), "assigned DOWN was not consumed");
        check(key(KeyEvent.ACTION_UP, code, 0, 1), "assigned UP was not consumed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void await(BooleanSupplier condition, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(condition.getAsBoolean(), failure);
    }
}
