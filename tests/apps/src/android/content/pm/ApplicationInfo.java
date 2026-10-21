package android.content.pm;
public class ApplicationInfo {
 public static final int FLAG_SYSTEM=1, FLAG_UPDATED_SYSTEM_APP=2, FLAG_PERSISTENT=4;
 public int uid, flags; public boolean enabled=true; public String label;
 public CharSequence loadLabel(PackageManager pm){return label;}
}
