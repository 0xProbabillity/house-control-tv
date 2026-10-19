package dev.housecontrol.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Home Assistant through its REST API with a long-lived access token, plus a WebSocket subscription to the linked
 * entities so a change from a wall switch shows within a second. Mirrors the upstream HomeAssistantService.
 * The token never leaves this class except in the Authorization header to the configured server.
 */
final class HomeAssistantClient {
    static final class NotFound extends IOException{NotFound(){super("Home Assistant does not know this device any more. Was it renamed?");}}
    final SecretStore vault;
    volatile JSONObject config;
    volatile String lastError="";
    volatile int commandAttempts=0;
    volatile Set<String> watched=Collections.emptySet();
    volatile Runnable onChange;
    volatile BooleanSupplier active=()->true;
    /** Each climate entity's own names for the panel's modes and fan speeds, from the last read. */
    final Map<String,JSONObject> modes=new HashMap<>(),fans=new HashMap<>();
    private Thread listener;private volatile int generation=0;
    HomeAssistantClient(SecretStore vault){this.vault=vault;try{config=vault.read();}catch(Exception e){config=null;lastError="Connect Home Assistant again.";}if(config!=null)listen();}
    boolean connected(){return config!=null;}
    JSONObject info()throws JSONException{JSONObject c=config;JSONObject out=new JSONObject().put("connected",c!=null).put("lastError",lastError);
        if(c!=null)out.put("url",c.optString("url")).put("name",c.optString("name","Home Assistant")).put("version",c.optString("version")).put("source",c.optString("source","tv"));return out;}
    /** A token sent over plain HTTP stays on the home network; HTTPS (a Nabu Casa or own domain) may be anywhere. */
    static void allowed(String url)throws IOException{URI u=URI.create(url);if(u.getScheme().equals("http"))LocalMapper.requirePrivate(u.getHost());}
    synchronized void connect(String url,String token)throws Exception{
        String u=LocalMapper.haURL(url),t=token==null?"":token.trim();
        if(!t.matches("[A-Za-z0-9._-]{20,2048}"))throw new IOException("Paste a long-lived access token: your profile in Home Assistant → Security → Long-lived access tokens.");
        allowed(u);JSONObject candidate=new JSONObject().put("url",u).put("token",t).put("source","tv");
        Object about=request("GET","/api/config",null,candidate);JSONObject a=about instanceof JSONObject?(JSONObject)about:new JSONObject();
        candidate.put("name",a.optString("location_name","Home Assistant")).put("version",a.optString("version"));
        vault.write(candidate);config=candidate;lastError="";modes.clear();fans.clear();listen();
    }
    /** Takes the connection the paired Mac shares; null removes one that came from the Mac. A TV's own connection wins. */
    synchronized boolean adopt(JSONObject shared)throws Exception{
        JSONObject c=config;if(c!=null&&!"mac".equals(c.optString("source")))return false;
        if(shared==null){if(c==null)return false;disconnect();return true;}
        String u=LocalMapper.haURL(shared.optString("url")),t=shared.optString("token");if(!t.matches("[A-Za-z0-9._-]{20,2048}"))throw new IOException("The Mac shared an invalid Home Assistant token.");allowed(u);
        if(c!=null&&c.optString("url").equals(u)&&c.optString("token").equals(t))return false;
        JSONObject next=new JSONObject().put("url",u).put("token",t).put("name",shared.optString("name","Home Assistant")).put("version",shared.optString("version")).put("source","mac");
        vault.write(next);config=next;lastError="";modes.clear();fans.clear();listen();return true;
    }
    synchronized void disconnect(){vault.clear();config=null;generation++;modes.clear();fans.clear();lastError="";}
    void watch(Set<String> ids){if(!ids.equals(watched)){watched=new HashSet<>(ids);generation++;}}

    /** Current state of the given entities as LocalMapper.haReading results. Entities Home Assistant does not know are left out. */
    Map<String,JSONObject> read(Set<String> ids)throws Exception{
        Map<String,JSONObject> out=new HashMap<>();List<JSONObject> states=new ArrayList<>();
        if(ids.size()>12){Object all=request("GET","/api/states",null,null);if(all instanceof JSONArray)for(int i=0;i<((JSONArray)all).length();i++){JSONObject s=((JSONArray)all).optJSONObject(i);if(s!=null&&ids.contains(s.optString("entity_id")))states.add(s);}}
        else for(String id:ids){if(!LocalMapper.entity(id))continue;try{Object s=request("GET","/api/states/"+id,null,null);if(s instanceof JSONObject)states.add((JSONObject)s);}catch(NotFound ignored){}}
        for(JSONObject s:states){String id=s.optString("entity_id");JSONObject r=LocalMapper.haReading(s);if(r.has("modes"))synchronized(this){modes.put(id,r.getJSONObject("modes"));fans.put(id,r.getJSONObject("fans"));}out.put(id,r);}
        return out;
    }
    void send(String id,JSONObject patch,JSONObject state)throws Exception{
        if(!LocalMapper.entity(id)||id.startsWith("sensor."))throw new IOException("This Home Assistant entity cannot be switched.");
        boolean known;synchronized(this){known=modes.containsKey(id);}if(id.startsWith("climate.")&&!known)read(Collections.singleton(id));
        JSONObject m,f;synchronized(this){m=modes.getOrDefault(id,new JSONObject());f=fans.getOrDefault(id,new JSONObject());}
        JSONArray calls=LocalMapper.haCalls(id,patch,state,f,m);
        for(int i=0;i<calls.length();i++){JSONObject c=calls.getJSONObject(i);commandAttempts++;request("POST","/api/services/"+c.getString("domain")+"/"+c.getString("service"),c.getJSONObject("data"),null);}
    }
    Object request(String method,String path,JSONObject body,JSONObject using)throws Exception{
        JSONObject c=using!=null?using:config;if(c==null)throw new IOException("Connect Home Assistant on this TV first.");
        HttpURLConnection h=(HttpURLConnection)new URL(c.getString("url")+path).openConnection(Proxy.NO_PROXY);
        h.setConnectTimeout(5000);h.setReadTimeout(9000);h.setInstanceFollowRedirects(false);h.setUseCaches(false);h.setRequestMethod(method);
        h.setRequestProperty("Authorization","Bearer "+c.getString("token"));h.setRequestProperty("Accept","application/json");
        try{
            if(body!=null){h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json");try(OutputStream o=h.getOutputStream()){o.write(body.toString().getBytes(SharingTransport.UTF8));}}
            int status=h.getResponseCode();
            if(status==401||status==403)throw new IOException("Home Assistant refused the token. Create a new long-lived access token and connect again.");
            if(status==404)throw new NotFound();
            InputStream stream=status<400?h.getInputStream():h.getErrorStream();byte[] data=new byte[0];
            if(stream!=null){ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=stream){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){b.write(buf,0,n);if(b.size()>6*1024*1024)throw new IOException("Home Assistant's answer is too large.");}}data=b.toByteArray();}
            if(status<200||status>=300)throw new IOException("Home Assistant answered "+status+(status==400?": it rejected this setting.":"."));
            String text=new String(data,SharingTransport.UTF8).trim();return text.isEmpty()?null:new JSONTokener(text).nextValue();
        }catch(SocketTimeoutException|ConnectException|UnknownHostException|NoRouteToHostException e){throw new IOException("Home Assistant at "+c.optString("url")+" did not answer.");}
        finally{h.disconnect();}
    }

    // --- Live updates ---
    private synchronized void listen(){generation++;if(listener!=null&&listener.isAlive())return;listener=new Thread(this::run,"homeassistant-events");listener.setDaemon(true);listener.start();}
    private void run(){
        while(true){
            int gen=generation;JSONObject c;Set<String> ids=watched;synchronized(this){c=config;if(c==null){listener=null;return;}}
            if(ids.isEmpty()||!active.getAsBoolean()){pause(gen,2000);continue;}
            boolean refused=false;try{refused=subscribe(c,ids,gen);}catch(Exception ignored){}
            pause(gen,refused?120000:10000); // the server restarted or the Wi-Fi dropped; polling covers the gap
        }
    }
    private void pause(int gen,long millis){long end=System.currentTimeMillis()+millis;while(gen==generation&&System.currentTimeMillis()<end)try{Thread.sleep(500);}catch(InterruptedException e){return;}}
    private void changed(){Runnable r=onChange;if(r!=null)r.run();}
    /** Returns true when the server refused the token (no quick retry). */
    boolean subscribe(JSONObject c,Set<String> ids,int gen)throws Exception{
        try(MiniWebSocket ws=MiniWebSocket.open(c.getString("url")+"/api/websocket")){
            ws.read(10000);ws.send(new JSONObject().put("type","auth").put("access_token",c.getString("token")).toString());
            String auth=ws.read(10000);if(auth==null||!auth.contains("\"auth_ok\"")){lastError="Home Assistant refused the token. Create a new long-lived access token and connect again.";return true;}
            ws.send(new JSONObject().put("id",1).put("type","subscribe_entities").put("entity_ids",new JSONArray(ids)).toString());boolean filter=false;
            while(gen==generation&&config==c&&active.getAsBoolean()){
                String text=ws.read(5000);if(text==null)continue;JSONObject m;try{m=new JSONObject(text);}catch(JSONException e){continue;}
                if(m.optString("type").equals("result")&&m.optInt("id")==1&&!m.optBoolean("success")){filter=true;ws.send(new JSONObject().put("id",2).put("type","subscribe_events").put("event_type","state_changed").toString());continue;} // before Home Assistant 2022.4
                if(!m.optString("type").equals("event"))continue;
                if(filter){JSONObject e=m.optJSONObject("event"),d=e==null?null:e.optJSONObject("data");if(d==null||!ids.contains(d.optString("entity_id")))continue;}
                changed();
            }
            return false;
        }
    }
}
