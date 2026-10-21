package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opens the selected app or live TV for the physical buttons described by RemoteProfile. The accessibility key filter sees
 * buttons that never reach an app's window, and consumes both halves of a remapped press before the TV handles it.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    // Keep the decision for the whole press, even if its assignment changes before ACTION_UP.
    // A small cap also bounds stale entries from disconnected remotes that never send ACTION_UP.
    private static final Map<Long, Boolean> presses = new LinkedHashMap<>();
    private static final Object launchLock = new Object();
    private static Launch pending;
    private static boolean workerRunning;
    private static volatile long latestLaunch;

    private static final class Launch {
        final Context context;
        final String target;
        final int keyCode;
        final long sequence;

        Launch(Context context, String target, int keyCode, long sequence) {
            this.context = context;
            this.target = target;
            this.keyCode = keyCode;
            this.sequence = sequence;
        }
    }

    static boolean supported() {
        return RemoteProfile.supported();
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0
                && (e.getKeyCode() == KeyEvent.KEYCODE_HOME || e.getKeyCode() == KeyEvent.KEYCODE_BACK
                || e.getKeyCode() == KeyEvent.KEYCODE_POWER)) {
            cancelPending(); // The user left or put the TV to sleep while a lookup was in progress.
            return false;
        }
        RemoteProfile.Button button = RemoteProfile.find(e.getKeyCode());
        if (button == null) return false;
        long press = ((long) e.getDeviceId() << 32) | (button.code & 0xffffffffL);
        synchronized (presses) {
            if (e.getAction() == KeyEvent.ACTION_UP) return Boolean.TRUE.equals(presses.remove(press));
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (e.getRepeatCount() != 0) return Boolean.TRUE.equals(presses.get(press));
            String target = Prefs.keyApp(button.code);
            boolean consume = !target.isEmpty(); // "System action" passes the entire press to the TV.
            if (!presses.containsKey(press) && presses.size() >= 32)
                presses.remove(presses.keySet().iterator().next());
            presses.put(press, consume);
            enqueue(c.getApplicationContext(), target, e.getKeyCode());
            return consume;
        }
    }

    /** The key filter has a 500 ms deadline. TV-provider and package-manager calls belong on a worker. */
    private static void enqueue(Context c, String target, int keyCode) {
        synchronized (launchLock) {
            // Only the latest request is useful. System action also cancels an older lookup so it
            // cannot open over the native app that the TV is about to launch for this new press.
            long sequence = ++latestLaunch;
            pending = target.isEmpty() ? null : new Launch(c, target, keyCode, sequence);
            if (pending == null || workerRunning) return;
            workerRunning = true;
            Thread worker = new Thread(RemoteKeys::launchPending, "remote-launch");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void cancelPending() {
        synchronized (launchLock) {
            latestLaunch++;
            pending = null;
        }
    }

    private static void launchPending() {
        while (true) {
            Launch request;
            synchronized (launchLock) {
                request = pending;
                pending = null;
                if (request == null) {
                    workerRunning = false;
                    return;
                }
            }
            try {
                Intent intent = LiveTv.TARGET.equals(request.target) ? LiveTv.intent(request.context)
                        : launchIntent(request.context.getPackageManager(), request.target);
                // A newer button press supersedes a slow provider lookup that has just finished.
                if (request.sequence != latestLaunch) continue;
                AppLog.w("remote key " + request.keyCode + " -> " + request.target
                        + (intent == null ? " (unavailable)" : ""));
                if (intent != null) request.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("couldn't open remote target " + request.target + ": " + e.getMessage());
            }
        }
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
