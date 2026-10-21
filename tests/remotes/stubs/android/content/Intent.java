package android.content;
public class Intent {
    public static final String ACTION_VIEW="view",ACTION_MAIN="main";
    public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_CLEAR_TOP=2,FLAG_ACTIVITY_SINGLE_TOP=4;
    public final String action;
    public String target;
    public Intent(String action) { this.action=action; }
    public Intent(Intent other) { action=other.action; target=other.target; }
    public Intent setDataAndType(Object uri,String type) { return this; }
    public Intent setPackage(String name) { target=name; return this; }
    public Intent setComponent(ComponentName component) { return this; }
    public Intent addFlags(int flags) { return this; }
    public Intent putExtra(String key,boolean value) { return this; }
    public Intent putExtra(String key,String value) { return this; }
}
