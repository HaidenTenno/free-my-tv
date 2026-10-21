package android.content.pm;
import android.content.Intent; import java.util.*;
public class PackageManager {
 public static final int MATCH_DISABLED_COMPONENTS=1, GET_SERVICES=2;
 public final Map<String,PackageInfo> packages=new LinkedHashMap<>();
 public final Set<String> homes=new HashSet<>(), launchers=new HashSet<>();
 public final Map<String,Integer> reads=new HashMap<>();
 public int globalQueries;
 public List<ResolveInfo> queryIntentActivities(Intent i,int flags){
  if(i.pkg==null)globalQueries++;
  List<ResolveInfo> out=new ArrayList<>();
  for(String p:Intent.CATEGORY_HOME.equals(i.category)?homes:launchers)
   if(i.pkg==null||p.equals(i.pkg))out.add(new ResolveInfo(p));
  return out;
 }
 public PackageInfo getPackageInfo(String p,int flags)throws NameNotFoundException{
  reads.put(p,reads.getOrDefault(p,0)+1);
  PackageInfo info=packages.get(p);if(info==null)throw new NameNotFoundException();return info;
 }
 public static class NameNotFoundException extends Exception{}
}
