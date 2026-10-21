package android.content.pm;
import android.content.Intent;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
public class PackageManager {
    public static final int MATCH_DEFAULT_ONLY=1,GET_RESOLVED_FILTER=2,PERMISSION_GRANTED=0;
    public static final AtomicInteger appLookups=new AtomicInteger();
    public static volatile CountDownLatch entered,release;
    public ResolveInfo resolveActivity(Intent intent,int flags) { return new ResolveInfo(); }
    public List<ResolveInfo> queryIntentActivities(Intent intent,int flags) { return Collections.emptyList(); }
    public Intent getLeanbackLaunchIntentForPackage(String name) {
        appLookups.incrementAndGet();
        CountDownLatch notify=entered,wait=release;
        if(notify!=null) notify.countDown();
        if(wait!=null) try {
            if(!wait.await(5,TimeUnit.SECONDS)) throw new AssertionError("package manager was never released");
        } catch(InterruptedException exception) { throw new AssertionError(exception); }
        if(name.equals("broken.app")) throw new SecurityException("unavailable package");
        return new Intent("launch").setPackage(name);
    }
    public Intent getLaunchIntentForPackage(String name) { return new Intent("launch").setPackage(name); }
}
