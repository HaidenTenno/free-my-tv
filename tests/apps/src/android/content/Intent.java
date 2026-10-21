package android.content;
public class Intent {
 public static final String ACTION_MAIN="main", CATEGORY_HOME="home", CATEGORY_LEANBACK_LAUNCHER="tv";
 public String category, pkg;
 public Intent(String action){}
 public Intent addCategory(String v){category=v;return this;}
 public Intent setPackage(String v){pkg=v;return this;}
}
