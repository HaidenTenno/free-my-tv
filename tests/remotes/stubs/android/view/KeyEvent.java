package android.view;
public class KeyEvent {
    public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MULTIPLE=2;
    public static final int KEYCODE_HOME=3,KEYCODE_BACK=4,KEYCODE_POWER=26,KEYCODE_VOLUME_UP=24;
    private final int action,code,repeat,device,source;
    public KeyEvent(int action,int code,int repeat,int device,int source) {
        this.action=action;this.code=code;this.repeat=repeat;this.device=device;this.source=source;
    }
    public int getSource() { return source; }
    public int getKeyCode() { return code; }
    public int getAction() { return action; }
    public int getRepeatCount() { return repeat; }
    public int getDeviceId() { return device; }
}
