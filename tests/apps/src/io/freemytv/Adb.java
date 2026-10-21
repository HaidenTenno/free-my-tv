package io.freemytv;
import android.content.Context; import java.io.IOException;
final class Adb {
 interface Handler {String shell(Adb adb,String command)throws IOException;}
 static Handler handler; static volatile Adb latest; boolean closed;
 static Adb connect(Context c,int ms,Object approval){return latest=new Adb();}
 String shell(String command)throws IOException {if(closed)throw new IOException("closed");return handler.shell(this,command);}
 synchronized void close(){closed=true;notifyAll();}
}
