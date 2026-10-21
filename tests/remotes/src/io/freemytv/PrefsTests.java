package io.freemytv;

import android.content.Context;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs real migration/file storage, including a read failure that must not overwrite saved choices. */
public final class PrefsTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Path.of(args[0]), "prefs-");
        Context context = new Context(directory.toFile());
        context.getSharedPreferences("settings", 0).edit()
                .putString("key4143", "saved.ivi").putString("key190", "saved.youtube")
                .putString("key9999", "saved.other-profile").putString("keyinvalid", "ignored")
                .putString("city", "Somewhere").apply();
        Prefs.init(context);
        Path file = directory.resolve("remote-keys.txt");
        check("saved.ivi".equals(Prefs.keyApp(4143)), "known legacy assignment lost");
        check("saved.youtube".equals(Prefs.keyApp(190)), "old TCL assignment lost on new profile");
        check("saved.other-profile".equals(Prefs.keyApp(9999)), "unknown profile assignment lost");
        check(!Files.readString(file).contains("ignored"), "non-numeric legacy key was migrated");
        Prefs.setKeyApp(4100, "new.okko");
        check("new.okko".equals(Prefs.keyApp(4100)), "new assignment not read back");
        check(Files.readString(file).contains("9999=saved.other-profile\n"), "editing discarded unknown key");
        check(Files.readString(file).contains("190=saved.youtube\n"), "editing discarded old TCL assignment");
        Prefs.setKeyApp(4100, null);
        check("".equals(Prefs.keyApp(4100)), "removing override did not restore default");
        check(LiveTv.TARGET.equals(Prefs.keyApp(4001)), "new TV button default missing");

        // A readable cache exists, then the next file read fails (a directory cannot be read as a file).
        Path backup = directory.resolve("saved.txt");
        Files.move(file, backup);
        Files.createDirectory(file);
        Prefs.setKeyApp(4143, "must.not.overwrite");
        check(Files.isDirectory(file), "failed read was followed by a write");
        check("saved.ivi".equals(Prefs.keyApp(4143)), "failed read destroyed last good cache");
        Files.delete(file);
        Files.move(backup, file);
        Prefs.setKeyApp(4143, "restored.ivi");
        check("restored.ivi".equals(Prefs.keyApp(4143)), "failed read prevented later recovery");
        check(Files.readString(file).contains("9999=saved.other-profile\n"), "recovery lost hidden assignment");
        System.out.println("remote preferences: " + checks + " checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
