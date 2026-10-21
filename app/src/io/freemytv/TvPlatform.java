package io.freemytv;

import android.os.Build;

/** TV firmware capabilities, independent of which regional remote is selected. */
final class TvPlatform {
    private TvPlatform() {}

    static final boolean TCL = maker("TCL"), VESTEL = maker("Vestel");
    static final boolean TCL_G08 = TCL && "G08".equalsIgnoreCase(Build.DEVICE);
    static final boolean HAIER_IRVINE = maker("Haier") && "irvine".equalsIgnoreCase(Build.DEVICE);

    private static boolean maker(String name) {
        return name.equalsIgnoreCase(Build.MANUFACTURER) || name.equalsIgnoreCase(Build.BRAND);
    }
}
