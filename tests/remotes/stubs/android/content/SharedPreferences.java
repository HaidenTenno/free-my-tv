package android.content;
import java.util.HashMap;
import java.util.Map;
public class SharedPreferences {
    private final Map<String,Object> values = new HashMap<>();
    public String getString(String key,String fallback) { return (String) values.getOrDefault(key,fallback); }
    public boolean getBoolean(String key,boolean fallback) { return (Boolean) values.getOrDefault(key,fallback); }
    public Map<String,?> getAll() { return new HashMap<>(values); }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        public Editor putString(String key,String value) { values.put(key,value); return this; }
        public Editor putBoolean(String key,boolean value) { values.put(key,value); return this; }
        public void apply() {}
    }
}
