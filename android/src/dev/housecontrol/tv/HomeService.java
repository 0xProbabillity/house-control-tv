package dev.housecontrol.tv;

import android.app.Service;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import org.json.*;
import java.security.MessageDigest;

/** Bound by Luma while the launcher is visible. Keeps TV-owned control alive without the Mac. */
public final class HomeService extends Service {
    static final String ACTIVITY_BIND="dev.housecontrol.tv.ACTIVITY_BIND";
    static HomeEngine sharedEngine;static TvServer sharedServer;
    static synchronized void initialize(Context c)throws Exception{
        if(sharedEngine==null)sharedEngine=new HomeEngine(c);
        if(sharedServer==null){android.content.SharedPreferences p=c.getSharedPreferences("connection",MODE_PRIVATE);String key=p.getString("remoteKey","");if(key.isEmpty()){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);key=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);p.edit().putString("remoteKey",key).apply();}sharedServer=new TvServer(c.getApplicationContext(),sharedEngine,key);}
    }
    static synchronized void replaceServer(Context c,String key)throws Exception{if(sharedServer!=null)sharedServer.close();sharedServer=new TvServer(c.getApplicationContext(),sharedEngine,key);}
    private boolean trusted(int uid){
        if(uid==android.os.Process.myUid())return true;
        try{String[] packages=getPackageManager().getPackagesForUid(uid);if(packages==null)return false;for(String name:packages)if(name.equals("dev.luma.tv")){
            PackageInfo p=getPackageManager().getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES);
            for(Signature signature:p.signingInfo.getApkContentsSigners())if(java.util.Arrays.asList(Trust.LUMA).contains(SharingTransport.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))))return true;
        }}catch(Exception ignored){}return false;
    }
    @Override public void onCreate(){super.onCreate();try{initialize(this);}catch(Exception ignored){}}
    @Override public IBinder onBind(Intent intent){if(sharedEngine!=null&&!ACTIVITY_BIND.equals(intent.getAction()))sharedEngine.setWidgetActive(true);return new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(!trusted(Binder.getCallingUid())){reply.writeException(new SecurityException("Only the paired Luma launcher may access this home."));return true;}
            JSONObject result;
            try{
                if(sharedEngine==null)throw new IllegalStateException("Open House Control to finish TV setup.");
                if(code==1){result=sharedEngine.home();}
                else if(code==2){String id=data.readString();result=sharedEngine.action(new JSONObject().put("kind","scene").put("id",id));}
                else if(code==4){String id=data.readString();result=sharedEngine.demoAction(new JSONObject().put("kind","scene").put("id",id));}
                else if(code==3){sharedEngine.setWidgetActive(data.readInt()!=0);result=new JSONObject().put("ok",true);}
                else return super.onTransact(code,data,reply,flags);
            }catch(Exception error){try{result=new JSONObject().put("error",HomeEngine.safe(error));}catch(Exception ignored){result=new JSONObject();}}
            reply.writeNoException();reply.writeString(result.toString());return true;
        }
    };}
    @Override public boolean onUnbind(Intent intent){if(sharedEngine!=null&&!ACTIVITY_BIND.equals(intent.getAction()))sharedEngine.setWidgetActive(false);return true;}
    @Override public void onRebind(Intent intent){if(sharedEngine!=null&&!ACTIVITY_BIND.equals(intent.getAction()))sharedEngine.setWidgetActive(true);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_NOT_STICKY;}
    @Override public void onDestroy(){if(sharedServer!=null)sharedServer.close();if(sharedEngine!=null)sharedEngine.close();sharedServer=null;sharedEngine=null;super.onDestroy();}
}
