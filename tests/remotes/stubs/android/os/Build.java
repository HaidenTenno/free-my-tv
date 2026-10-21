package android.os;
public class Build {
    public static String MANUFACTURER = System.getProperty("test.manufacturer", "TCL");
    public static String BRAND = System.getProperty("test.brand", "TCL");
    public static String DEVICE = System.getProperty("test.device", "G08");
}
