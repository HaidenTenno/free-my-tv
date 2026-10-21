package android.content;
import android.database.Cursor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
public class ContentResolver {
    public static volatile CountDownLatch entered, release;
    public static final AtomicInteger queries = new AtomicInteger();
    public Cursor query(Object uri,String[] projection,String selection,String[] arguments,String order) {
        queries.incrementAndGet();
        CountDownLatch notify=entered, wait=release;
        if(notify!=null) notify.countDown();
        if(wait!=null) try {
            if(!wait.await(5,TimeUnit.SECONDS)) throw new AssertionError("provider was never released");
        } catch(InterruptedException exception) { throw new AssertionError(exception); }
        return new Cursor();
    }
}
