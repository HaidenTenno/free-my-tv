package android.view.accessibility;
import android.view.View; import android.graphics.Rect; import java.util.*;
public class AccessibilityNodeInfo {
 public static final int ACTION_CLICK=16,ACTION_LONG_CLICK=32,ACTION_FOCUS=1,ACTION_ACCESSIBILITY_FOCUS=64,ACTION_CLEAR_ACCESSIBILITY_FOCUS=128;
 public static class AccessibilityAction {
  public final int id; public AccessibilityAction(int id){this.id=id;}
  public static final AccessibilityAction ACTION_CLICK=new AccessibilityAction(16), ACTION_LONG_CLICK=new AccessibilityAction(32), ACTION_FOCUS=new AccessibilityAction(1), ACTION_ACCESSIBILITY_FOCUS=new AccessibilityAction(64), ACTION_CLEAR_ACCESSIBILITY_FOCUS=new AccessibilityAction(128);
 }
 public final Set<Integer> actions=new HashSet<>(); public boolean longClickable;
 public static AccessibilityNodeInfo obtain(View v){return new AccessibilityNodeInfo();} public static AccessibilityNodeInfo obtain(View v,int id){return new AccessibilityNodeInfo();}
 public void addChild(View v,int id){} public void setPackageName(String v){} public void setClassName(String v){} public void setParent(View v){} public void setText(String v){}
 public void setBoundsInParent(Rect r){} public void setBoundsInScreen(Rect r){} public void setEnabled(boolean v){} public void setVisibleToUser(boolean v){} public void setImportantForAccessibility(boolean v){}
 public void setFocusable(boolean v){} public void setFocused(boolean v){} public void setClickable(boolean v){} public void setLongClickable(boolean v){longClickable=v;}
 public void addAction(AccessibilityAction action){actions.add(action.id);} public void setAccessibilityFocused(boolean v){}
}
