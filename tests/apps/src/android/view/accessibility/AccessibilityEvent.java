package android.view.accessibility;
import android.view.View; import java.util.*;
public class AccessibilityEvent {
 public static final int TYPE_WINDOW_CONTENT_CHANGED=1,TYPE_VIEW_FOCUSED=2,TYPE_VIEW_ACCESSIBILITY_FOCUSED=3,TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED=4,CONTENT_CHANGE_TYPE_SUBTREE=1;
 public static AccessibilityEvent obtain(int type){return new AccessibilityEvent();} public void setContentChangeTypes(int type){} public void setSource(View host,int id){}
 public void setPackageName(String v){} public void setClassName(String v){} public void setEnabled(boolean v){} public List<CharSequence> getText(){return new ArrayList<>();}
}
