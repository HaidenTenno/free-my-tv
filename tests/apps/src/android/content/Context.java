package android.content;
import android.content.pm.*;
import android.view.accessibility.AccessibilityManager;
public class Context {
 public static final String ACCESSIBILITY_SERVICE="accessibility";
 public PackageManager pm=new PackageManager();
 public ApplicationInfo own=new ApplicationInfo();
 public Context(){own.uid=10001;}
 public Context getApplicationContext(){return this;}
 public String getPackageName(){return "io.freemytv";}
 public ApplicationInfo getApplicationInfo(){return own;}
 public PackageManager getPackageManager(){return pm;}
 public Object getSystemService(String name){return new AccessibilityManager();}
}
