package dev.housecontrol.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;

/**
 * A Philips Hue Bridge on the local network through CLIP v2. The bridge hands out an app key when its round button is
 * pressed. Its certificate is self-signed: it is trusted the first time and only that certificate afterwards (SHA-256
 * pin, as the upstream HueService), so another device answering at the bridge's address is refused.
 */
final class HueClient {
    static final class ButtonNotPressed extends IOException{ButtonNotPressed(){super("Press the round button on the Hue Bridge.");}}
    /** Trusts exactly the pinned certificate; without a pin (pairing), remembers the one it saw. */
    static final class Pin implements X509TrustManager {
        final String expected;volatile String seen;
        Pin(String expected){this.expected=expected==null||expected.isEmpty()?null:expected;}
        static String fingerprint(java.security.cert.Certificate c)throws CertificateException{try{return SharingTransport.hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));}catch(NoSuchAlgorithmException e){throw new CertificateException(e);}}
        @Override public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{
            if(chain==null||chain.length==0)throw new CertificateException("The bridge sent no certificate.");String f=fingerprint(chain[0]);seen=f;
            if(expected!=null&&!MessageDigest.isEqual(expected.getBytes(SharingTransport.UTF8),f.getBytes(SharingTransport.UTF8)))throw new CertificateException("This is not the paired Hue Bridge.");
        }
        @Override public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{throw new CertificateException("Not a server.");}
        @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
    }
    final SecretStore vault;
    volatile JSONObject config;
    volatile String lastError="";
    volatile int commandAttempts=0;
    volatile Set<String> watched=Collections.emptySet();
    volatile Runnable onChange;
    volatile BooleanSupplier active=()->true;
    private Thread listener;private volatile int generation=0;
    HueClient(SecretStore vault){this.vault=vault;try{config=vault.read();}catch(Exception e){config=null;lastError="Pair the Hue Bridge again.";}if(config!=null)listen();}
    boolean connected(){return config!=null;}
    JSONObject info()throws JSONException{JSONObject c=config;JSONObject out=new JSONObject().put("connected",c!=null).put("lastError",lastError);
        if(c!=null)out.put("host",c.optString("host")).put("name",c.optString("name","Hue Bridge")).put("bridgeId",c.optString("bridgeId")).put("source",c.optString("source","tv"));return out;}

    /** Asks the bridge for a key. Throws ButtonNotPressed until someone presses its button (pairing then stays open 30 s). */
    synchronized void pair(String raw,String bridgeId,String name)throws Exception{
        String host=LocalMapper.hueHost(raw);LocalMapper.requirePrivate(host);Pin pin=new Pin(null);
        JSONObject wanted=new JSONObject().put("devicetype","house_control#TV").put("generateclientkey",true);Object answer;
        try{answer=call(host,null,pin,"POST","/api",wanted);}catch(SSLException e){throw new IOException("The device at "+host+" is not a Hue Bridge.");}
        JSONObject first=answer instanceof JSONArray?((JSONArray)answer).optJSONObject(0):null;if(first==null)throw new IOException("The device at "+host+" did not answer like a Hue Bridge.");
        JSONObject error=first.optJSONObject("error");if(error!=null){if(error.optInt("type")==101)throw new ButtonNotPressed();throw new IOException("The bridge said: "+error.optString("description","error"));}
        JSONObject success=first.optJSONObject("success");String key=success==null?"":success.optString("username");if(!key.matches("[A-Za-z0-9_-]{8,128}")||pin.seen==null)throw new IOException("The device at "+host+" did not answer like a Hue Bridge.");
        JSONObject next=new JSONObject().put("host",host).put("key",key).put("certificate",pin.seen).put("bridgeId",bridgeId==null?"":bridgeId).put("name",name==null||name.isEmpty()?"Hue Bridge":name).put("source","tv");
        try{JSONArray bridges=data(next,"/clip/v2/resource/bridge"),devices=data(next,"/clip/v2/resource/device");JSONObject bridge=bridges.optJSONObject(0);
            if(bridge!=null){next.put("bridgeId",bridge.optString("bridge_id",next.optString("bridgeId")));for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);JSONArray s=d.optJSONArray("services");if(s!=null)for(int n=0;n<s.length();n++)if(bridge.optString("id").equals(s.getJSONObject(n).optString("rid"))&&d.optJSONObject("metadata")!=null)next.put("name",d.getJSONObject("metadata").optString("name","Hue Bridge"));}}
        }catch(Exception ignored){} // the name is only for display
        vault.write(next);config=next;lastError="";listen();
    }
    /** Takes the pairing the Mac shares (its certificate pin included); null removes one that came from the Mac. */
    synchronized boolean adopt(JSONObject shared)throws Exception{
        JSONObject c=config;if(c!=null&&!"mac".equals(c.optString("source")))return false;
        if(shared==null){if(c==null)return false;disconnect();return true;}
        String host=LocalMapper.hueHost(shared.optString("host")),key=shared.optString("key"),cert=shared.optString("certificate");
        if(!key.matches("[A-Za-z0-9_-]{8,128}")||!(cert.isEmpty()||cert.matches("[a-f0-9]{64}")))throw new IOException("The Mac shared an invalid Hue pairing.");LocalMapper.requirePrivate(host);
        if(c!=null&&c.optString("host").equals(host)&&c.optString("key").equals(key)&&c.optString("certificate").equals(cert))return false;
        JSONObject next=new JSONObject().put("host",host).put("key",key).put("certificate",cert).put("bridgeId",shared.optString("bridgeId")).put("name",shared.optString("name","Hue Bridge")).put("source","mac");
        vault.write(next);config=next;lastError="";listen();return true;
    }
    synchronized void disconnect(){vault.clear();config=null;generation++;lastError="";}
    void watch(Set<String> ids){if(!ids.equals(watched)){watched=new HashSet<>(ids);generation++;}}

    /** Lamps as {light:{…}} and thermometers as {temperature:°C}, by resource id. */
    Map<String,JSONObject> read(Set<String> lights,Set<String> sensors)throws Exception{
        Map<String,JSONObject> out=new HashMap<>();JSONObject c=config;
        if(!lights.isEmpty()){JSONArray a=data(c,"/clip/v2/resource/light");for(int i=0;i<a.length();i++){JSONObject l=a.getJSONObject(i);if(lights.contains(l.optString("id")))out.put(l.getString("id"),new JSONObject().put("online",true).put("light",LocalMapper.hueLight(l)));}}
        if(!sensors.isEmpty()){JSONArray a=data(c,"/clip/v2/resource/temperature");for(int i=0;i<a.length();i++){JSONObject s=a.getJSONObject(i);Double t=LocalMapper.hueTemperature(s);if(sensors.contains(s.optString("id"))&&t!=null)out.put(s.getString("id"),new JSONObject().put("online",true).put("temperature",t));}}
        return out;
    }
    void send(String id,JSONObject patch)throws Exception{
        if(!LocalMapper.hueId(id))throw new IOException("Unknown Hue lamp.");JSONObject body=LocalMapper.hueBody(patch);if(body.length()==0)return;
        commandAttempts++;Object answer=call(null,config,null,"PUT","/clip/v2/resource/light/"+id,body);
        JSONArray errors=answer instanceof JSONObject?((JSONObject)answer).optJSONArray("errors"):null;if(errors!=null&&errors.length()>0)throw new IOException("Hue: "+errors.getJSONObject(0).optString("description","the bridge refused the change"));
    }
    JSONArray data(JSONObject c,String path)throws Exception{Object a=call(null,c,null,"GET",path,null);JSONArray d=a instanceof JSONObject?((JSONObject)a).optJSONArray("data"):null;return d==null?new JSONArray():d;}

    static HttpsURLConnection open(String host,Pin pin,String path,int readTimeout)throws Exception{
        HttpsURLConnection h=(HttpsURLConnection)new URL("https://"+host+path).openConnection(Proxy.NO_PROXY);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{pin},null);h.setSSLSocketFactory(tls.getSocketFactory());
        h.setHostnameVerifier((name,session)->true); // the certificate names the bridge id, not its address; the pin is the check
        h.setConnectTimeout(5000);h.setReadTimeout(readTimeout);h.setInstanceFollowRedirects(false);h.setUseCaches(false);return h;
    }
    /** `pin` is null for a paired bridge (its saved certificate is required); pairing passes an unpinned one. */
    Object call(String host,JSONObject c,Pin pin,String method,String path,JSONObject body)throws Exception{
        if(host==null){if(c==null)throw new IOException("Pair a Hue Bridge on this TV first.");host=c.getString("host");pin=new Pin(c.optString("certificate"));}
        HttpsURLConnection h=open(host,pin,path,9000);h.setRequestMethod(method);if(c!=null)h.setRequestProperty("hue-application-key",c.getString("key"));
        try{
            if(body!=null){h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json");try(OutputStream o=h.getOutputStream()){o.write(body.toString().getBytes(SharingTransport.UTF8));}}
            int status=h.getResponseCode();if(c!=null&&(status==401||status==403))throw new IOException("The Hue Bridge no longer accepts this TV’s key. Pair it again.");
            InputStream stream=status<400?h.getInputStream():h.getErrorStream();ByteArrayOutputStream b=new ByteArrayOutputStream();
            if(stream!=null)try(InputStream in=stream){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){b.write(buf,0,n);if(b.size()>4*1024*1024)throw new IOException("The bridge's answer is too large.");}}
            String text=new String(b.toByteArray(),SharingTransport.UTF8).trim();Object json=text.isEmpty()?null:new JSONTokener(text).nextValue();
            if(status<200||status>=300){JSONArray e=json instanceof JSONObject?((JSONObject)json).optJSONArray("errors"):null;throw new IOException("Hue: "+(e!=null&&e.length()>0?e.getJSONObject(0).optString("description"):"status "+status));}
            if(c!=null&&c.optString("certificate").isEmpty()&&pin.seen!=null)remember(c,pin.seen);
            return json;
        }catch(SSLException e){throw new IOException("The device at "+host+" is not the paired Hue Bridge.");}
        catch(SocketTimeoutException|ConnectException|NoRouteToHostException e){throw new IOException("The Hue Bridge at "+host+" did not answer.");}
        finally{h.disconnect();}
    }
    /** A pairing shared without a certificate pin is pinned on its first answer. */
    private synchronized void remember(JSONObject c,String fingerprint)throws Exception{if(config!=c)return;JSONObject next=CloudMapper.copy(c).put("certificate",fingerprint);vault.write(next);config=next;}

    // --- Event stream ---
    private synchronized void listen(){generation++;if(listener!=null&&listener.isAlive())return;listener=new Thread(this::run,"hue-events");listener.setDaemon(true);listener.start();}
    private void run(){
        while(true){
            int gen=generation;JSONObject c;Set<String> ids=watched;synchronized(this){c=config;if(c==null){listener=null;return;}}
            if(ids.isEmpty()||!active.getAsBoolean()||c.optString("certificate").isEmpty()){pause(gen,2000);continue;}
            try{stream(c,ids,gen);}catch(Exception ignored){}
            pause(gen,5000); // the bridge restarted or the Wi-Fi dropped; polling covers the gap
        }
    }
    private void pause(int gen,long millis){long end=System.currentTimeMillis()+millis;while(gen==generation&&System.currentTimeMillis()<end)try{Thread.sleep(500);}catch(InterruptedException e){return;}}
    void stream(JSONObject c,Set<String> ids,int gen)throws Exception{
        HttpsURLConnection h=open(c.getString("host"),new Pin(c.optString("certificate")),"/eventstream/clip/v2",45000);
        h.setRequestProperty("hue-application-key",c.getString("key"));h.setRequestProperty("Accept","text/event-stream");
        try(BufferedReader in=new BufferedReader(new InputStreamReader(h.getInputStream(),SharingTransport.UTF8))){String line;
            while(gen==generation&&config==c&&active.getAsBoolean()&&(line=in.readLine())!=null){if(!line.startsWith("data:"))continue;for(String id:ids)if(line.contains(id)){Runnable r=onChange;if(r!=null)r.run();break;}}
        }finally{h.disconnect();}
    }
}
