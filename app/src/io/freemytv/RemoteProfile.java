package io.freemytv;

import android.os.Build;

/** Button names and codes belong together: regional remotes from one maker can be entirely different. */
final class RemoteProfile {
    private RemoteProfile() {}

    static final boolean TCL = maker("TCL"), VESTEL = maker("Vestel");
    static final boolean TCL_G08 = TCL && "G08".equalsIgnoreCase(Build.DEVICE);
    static final boolean HAIER_IRVINE = maker("Haier") && "irvine".equalsIgnoreCase(Build.DEVICE);

    static final class Button {
        final int code;
        final String portuguese, english, defaultApp;
        final int[] aliases;

        Button(int code, String portuguese, String english, String defaultApp, int... aliases) {
            this.code = code;
            this.portuguese = portuguese;
            this.english = english;
            this.defaultApp = defaultApp;
            this.aliases = aliases;
        }

        boolean matches(int keyCode) {
            if (code == keyCode) return true;
            for (int alias : aliases) if (alias == keyCode) return true;
            return false;
        }
    }

    // Measured on the user's G08 / BT_RC813A_B9. The blank default preserves the TV's own action.
    private static final Button[] TCL_RU = {
            new Button(4143, "Botão Иви", "Иви button", ""),
            new Button(4100, "Botão Okko", "Okko button", ""),
            new Button(4185, "Botão PREMIER", "PREMIER button", ""),
            new Button(4164, "Botão Wink", "Wink button", ""),
            new Button(4020, "Botão TCL Channel", "TCL Channel button", ""),
            new Button(4186, "Botão VK Vídeo", "VK Video button", "")
    };

    // Measured on Haier OLED TV AE / HaierTV-RC Consumer Control. These are NOT the standard Android meanings.
    private static final Button[] HAIER_RU = {
            new Button(249, "Botão Prime Video", "Prime Video button", ""),
            new Button(129, "Botão EVO", "EVO button", ""),
            new Button(200, "Botão Wink", "Wink button", ""),
            new Button(250, "Botão Okko", "Okko button", ""),
            new Button(265, "Botão Netflix", "Netflix button", ""),
            new Button(247, "Botão YouTube", "YouTube button", "")
    };

    private static final Button[] TCL_STANDARD = {
            new Button(4062, "Botão Netflix", "Netflix button", "com.netflix.ninja"),
            new Button(190, "Botão YouTube", "YouTube button", "com.google.android.youtube.tv", 4065),
            new Button(4099, "Botão Prime Video", "Prime Video button", "com.amazon.amazonvideo.livingroom"),
            new Button(4020, "Botão TCL Channel", "TCL Channel button", "")
    };

    static final Button[] BUTTONS = TCL_G08 ? TCL_RU : HAIER_IRVINE ? HAIER_RU : VESTEL
            ? new Button[] {new Button(265, "Botão Netflix", "Netflix button", "com.netflix.ninja")}
            : TCL_STANDARD;

    static boolean supported() { return TCL || VESTEL || HAIER_IRVINE; }

    static Button find(int keyCode) {
        for (Button button : BUTTONS) if (button.matches(keyCode)) return button;
        return null;
    }

    static int[] codes() {
        int[] codes = new int[BUTTONS.length];
        for (int i = 0; i < codes.length; i++) codes[i] = BUTTONS[i].code;
        return codes;
    }

    private static boolean maker(String name) {
        return name.equalsIgnoreCase(Build.MANUFACTURER) || name.equalsIgnoreCase(Build.BRAND);
    }
}
