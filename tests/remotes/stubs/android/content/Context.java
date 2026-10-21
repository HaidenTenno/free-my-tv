package android.content;
import android.content.pm.PackageManager;
import android.media.tv.TvInputManager;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
public class Context {
    public static final String TV_INPUT_SERVICE = "tv";
    public static final int MODE_PRIVATE = 0;
    public final List<Intent> launches = new CopyOnWriteArrayList<>();
    private final File files;
    private final SharedPreferences preferences = new SharedPreferences();
    public Context(File files) { this.files = files; files.mkdirs(); }
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { return files; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    public PackageManager getPackageManager() { return new PackageManager(); }
    public Object getSystemService(String key) { return new TvInputManager(); }
    public ContentResolver getContentResolver() { return new ContentResolver(); }
    public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
    public void startActivity(Intent intent) { launches.add(intent); }
}
