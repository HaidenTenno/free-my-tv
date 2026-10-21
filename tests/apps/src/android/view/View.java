package android.view;
import android.content.Context; import android.os.Bundle; import android.view.accessibility.*;
public class View {
 private final Context context; public View(Context c){context=c;} public Context getContext(){return context;}
 public void removeCallbacks(Runnable r){} public void postDelayed(Runnable r,long ms){}
 public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo i){} public void getLocationOnScreen(int[] out){}
 public boolean performAccessibilityAction(int id,Bundle args){return false;}
 public ViewParent getParent(){return null;} public void onInitializeAccessibilityEvent(AccessibilityEvent e){}
}
