package io.freemytv;

import java.util.Arrays;

/** Each scenario runs in a fresh JVM, matching Android's immutable Build values per app process. */
public final class ProfileTests {
    public static void main(String[] args) {
        switch (args[0]) {
            case "tcl-ru":
                codes(4143, 4100, 4185, 4164, 4020, 4186, 4001);
                check(RemoteProfile.supported() && TvPlatform.TCL_G08, "TCL platform/profile not recognized");
                check(LiveTv.TARGET.equals(RemoteProfile.find(4001).defaultApp), "TCL TV default changed");
                for (int code : new int[] {4143, 4100, 4185, 4164, 4020, 4186})
                    check(RemoteProfile.find(code).defaultApp.isEmpty(), "TCL app default changed");
                break;
            case "haier":
                codes(249, 129, 200, 250, 265, 247, 172);
                check(RemoteProfile.supported() && TvPlatform.HAIER_IRVINE, "Haier platform/profile not recognized");
                check(LiveTv.TARGET.equals(RemoteProfile.find(172).defaultApp), "Haier TV default changed");
                for (int code : new int[] {249, 129, 200, 250, 265, 247})
                    check(RemoteProfile.find(code).defaultApp.isEmpty(), "Haier app default changed");
                break;
            case "tcl-standard":
                codes(4062, 190, 4099, 4020);
                check(RemoteProfile.supported() && !TvPlatform.TCL_G08, "standard TCL selection changed");
                check(RemoteProfile.find(4065) == RemoteProfile.find(190), "TCL YouTube alias lost");
                check("com.google.android.youtube.tv".equals(RemoteProfile.find(190).defaultApp), "YouTube default changed");
                check("com.netflix.ninja".equals(RemoteProfile.find(4062).defaultApp), "Netflix default changed");
                check("com.amazon.amazonvideo.livingroom".equals(RemoteProfile.find(4099).defaultApp), "Prime default changed");
                break;
            case "vestel":
                codes(265);
                check(RemoteProfile.supported() && TvPlatform.VESTEL, "Vestel manufacturer ignored");
                check("com.netflix.ninja".equals(RemoteProfile.find(265).defaultApp), "Vestel default changed");
                break;
            case "unsupported":
                check(!RemoteProfile.supported(), "unsupported TV remapping was enabled");
                check(!TvPlatform.TCL_G08 && !TvPlatform.HAIER_IRVINE, "device matched without maker");
                break;
            default: throw new AssertionError("unknown scenario");
        }
        check(RemoteProfile.find(123456) == null, "unknown key unexpectedly matched");
        System.out.println("remote profile " + args[0] + ": passed");
    }

    private static void codes(int... expected) {
        check(Arrays.equals(expected, RemoteProfile.codes()), "button codes/order changed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
