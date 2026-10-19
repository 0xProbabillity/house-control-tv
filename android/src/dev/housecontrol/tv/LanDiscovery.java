package dev.housecontrol.tv;

import android.content.Context;
import android.net.nsd.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Finds Home Assistant servers and Hue Bridges with mDNS, like the upstream ServiceDiscovery, so pairing is a press rather than typing. */
final class LanDiscovery {
    static String text(Map<String,byte[]> txt,String key){byte[] v=txt==null?null:txt.get(key);return v==null?"":new String(v,SharingTransport.UTF8);}
    static List<NsdServiceInfo> browse(NsdManager nsd,String type,long millis)throws InterruptedException{
        List<NsdServiceInfo> found=Collections.synchronizedList(new ArrayList<>());
        NsdManager.DiscoveryListener l=new NsdManager.DiscoveryListener(){
            public void onStartDiscoveryFailed(String t,int e){}public void onStopDiscoveryFailed(String t,int e){}public void onDiscoveryStarted(String t){}public void onDiscoveryStopped(String t){}
            public void onServiceFound(NsdServiceInfo s){found.add(s);}public void onServiceLost(NsdServiceInfo s){}
        };
        nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,l);Thread.sleep(millis);try{nsd.stopServiceDiscovery(l);}catch(Exception ignored){}
        List<NsdServiceInfo> resolved=new ArrayList<>();Set<String> names=new HashSet<>();
        for(NsdServiceInfo s:new ArrayList<>(found)){if(!names.add(s.getServiceName()))continue;NsdServiceInfo r=resolve(nsd,s);if(r==null){Thread.sleep(300);r=resolve(nsd,s);}if(r!=null)resolved.add(r);}
        return resolved;
    }
    /** Older Android resolves one service at a time across the whole device: browses run in parallel, resolves take turns. */
    static synchronized NsdServiceInfo resolve(NsdManager nsd,NsdServiceInfo s)throws InterruptedException{
        CountDownLatch done=new CountDownLatch(1);NsdServiceInfo[] out=new NsdServiceInfo[1];
        nsd.resolveService(s,new NsdManager.ResolveListener(){public void onResolveFailed(NsdServiceInfo i,int e){done.countDown();}public void onServiceResolved(NsdServiceInfo i){out[0]=i;done.countDown();}});
        return done.await(2500,TimeUnit.MILLISECONDS)&&out[0]!=null&&out[0].getHost()!=null?out[0]:null;
    }
    static String address(InetAddress a){return a instanceof Inet6Address?"["+a.getHostAddress().replaceAll("%.*","")+"]":a.getHostAddress();}
    static JSONObject find(Context context)throws Exception{
        NsdManager nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<List<NsdServiceInfo>> ha=pool.submit(()->browse(nsd,"_home-assistant._tcp",3000)),hue=pool.submit(()->browse(nsd,"_hue._tcp",3000));
            JSONArray servers=new JSONArray(),bridges=new JSONArray();
            for(NsdServiceInfo s:ha.get(20,TimeUnit.SECONDS)){Map<String,byte[]> t=s.getAttributes();String url=text(t,"internal_url");if(url.isEmpty())url=text(t,"base_url");if(url.isEmpty())url="http://"+address(s.getHost())+":"+s.getPort();
                String name=text(t,"location_name");servers.put(new JSONObject().put("name",name.isEmpty()?s.getServiceName():name).put("url",url).put("version",text(t,"version")));}
            for(NsdServiceInfo s:hue.get(20,TimeUnit.SECONDS)){String host=address(s.getHost());bridges.put(new JSONObject().put("name",s.getServiceName()).put("host",s.getPort()==443?host:host+":"+s.getPort()).put("bridgeId",text(s.getAttributes(),"bridgeid")));}
            if(bridges.length()==0)try{JSONArray cloud=cloudBridges();for(int i=0;i<cloud.length();i++)bridges.put(cloud.get(i));}catch(Exception ignored){} // networks that block mDNS
            return new JSONObject().put("homeassistant",servers).put("hue",bridges);
        }finally{pool.shutdownNow();}
    }
    /** Philips' discovery service: the bridges that reported this network's public address. */
    static JSONArray cloudBridges()throws Exception{
        HttpURLConnection h=(HttpURLConnection)new URL("https://discovery.meethue.com/").openConnection();h.setConnectTimeout(4000);h.setReadTimeout(4000);h.setInstanceFollowRedirects(false);
        try(InputStream in=h.getInputStream()){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1&&b.size()<65536)b.write(buf,0,n);
            JSONArray list=new JSONArray(new String(b.toByteArray(),SharingTransport.UTF8)),out=new JSONArray();
            for(int i=0;i<list.length()&&i<10;i++){JSONObject x=list.optJSONObject(i);if(x!=null&&x.optString("internalipaddress").matches("[0-9.]{7,15}"))out.put(new JSONObject().put("name","Hue Bridge").put("host",x.getString("internalipaddress")).put("bridgeId",x.optString("id")));}return out;
        }finally{h.disconnect();}
    }
}
