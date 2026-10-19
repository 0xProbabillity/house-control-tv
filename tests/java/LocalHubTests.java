package dev.housecontrol.tv;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
/** Home Assistant and Hue clients against tools/mock_hubs.py on loopback. No real device is involved. */
public class LocalHubTests {
 static int tests=0;
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);tests++;}
 static final class MemoryStore implements SecretStore{JSONObject value;MemoryStore(JSONObject v){value=v;}public JSONObject read(){return value;}public void write(JSONObject v){value=v;}public void clear(){value=null;}}
 static String failure(java.util.concurrent.Callable<?> c){try{c.call();return "";}catch(Exception e){return e.getMessage()==null?e.toString():e.getMessage();}}
 static String mock(String base,String path,JSONObject body)throws Exception{
  HttpURLConnection h=base.startsWith("https")?HueClient.open(base.substring(8),new HueClient.Pin(null),path,5000):(HttpURLConnection)new URL(base+path).openConnection(Proxy.NO_PROXY);
  if(body!=null){h.setRequestMethod("POST");h.setDoOutput(true);try(OutputStream o=h.getOutputStream()){o.write(body.toString().getBytes(SharingTransport.UTF8));}}
  try(InputStream in=h.getInputStream()){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return new String(b.toByteArray(),SharingTransport.UTF8);}finally{h.disconnect();}
 }
 static boolean waitFor(AtomicInteger counter,int above,long millis)throws InterruptedException{long end=System.currentTimeMillis()+millis;while(System.currentTimeMillis()<end){if(counter.get()>above)return true;Thread.sleep(100);}return false;}
 public static void main(String[] args)throws Exception{
  JSONObject info=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0])),SharingTransport.UTF8));
  String haURL=info.getString("homeassistant"),token=info.getString("token"),hueHost=info.getString("hue"),hueBase="https://"+hueHost;
  LocalMapper.allowLoopback=true;

  // Home Assistant
  HomeAssistantClient ha=new HomeAssistantClient(new MemoryStore(null));AtomicInteger changes=new AtomicInteger();ha.onChange=changes::incrementAndGet;
  check(failure(()->{ha.connect(haURL,"wrong-token-0123456789abcdef");return null;}).contains("refused the token")&&!ha.connected(),"HA wrong token is refused and not kept");
  check(failure(()->{ha.connect(haURL,"short");return null;}).contains("long-lived access token"),"HA token shape is checked before any request");
  Set<String> ids=new HashSet<>(Arrays.asList("light.living_lamp","switch.desk_plug","climate.bedroom_ac","sensor.balcony_temperature","light.renamed_lamp"));ha.watch(ids);
  ha.connect(haURL,token);check(ha.connected()&&ha.info().getString("name").equals("Mock Home")&&!ha.info().has("token")&&!ha.info().toString().contains(token),"HA connect keeps the token out of status");
  check(ha.vault.read().getString("token").equals(token),"HA token stored in the vault");
  Map<String,JSONObject> read=ha.read(ids);
  check(!read.containsKey("light.renamed_lamp")&&read.get("light.living_lamp").getJSONObject("light").getDouble("temp")==3000,"HA read maps a light and skips unknown entities");
  check(read.get("climate.bedroom_ac").getJSONObject("ac").getString("mode").equals("cool")&&Math.abs(read.get("sensor.balcony_temperature").getDouble("temperature")-20)<1e-9,"HA read maps climate and °F sensor");
  check(waitFor(changes,0,5000),"HA WebSocket subscription delivers the initial state");
  int before=changes.get();mock(haURL,"/mock/set",new JSONObject().put("id","light.living_lamp").put("state","off"));
  check(waitFor(changes,before,3000),"HA wall-switch change arrives through the WebSocket");
  check(!ha.read(Collections.singleton("light.living_lamp")).get("light.living_lamp").getJSONObject("light").getBoolean("on"),"HA read after the event shows the new state");
  ha.send("light.living_lamp",new JSONObject().put("on",true).put("brightness",.4).put("color","#ff0000"),new JSONObject());
  ha.send("climate.bedroom_ac",new JSONObject().put("mode","auto").put("fan","medium").put("target",21),new JSONObject().put("mode","cool"));
  ha.send("climate.bedroom_ac",new JSONObject().put("on",false),new JSONObject());
  String log=mock(haURL,"/mock/log",null);
  check(log.contains("light.turn_on {\\\"brightness_pct\\\": 40, \\\"entity_id\\\": \\\"light.living_lamp\\\", \\\"rgb_color\\\": [255, 0, 0]}"),"HA light command reaches the server");
  check(log.contains("climate.set_hvac_mode {\\\"entity_id\\\": \\\"climate.bedroom_ac\\\", \\\"hvac_mode\\\": \\\"heat_cool\\\"}")&&log.contains("\\\"fan_mode\\\": \\\"Mid\\\"")&&log.contains("\\\"temperature\\\": 21")&&log.contains("\\\"hvac_mode\\\": \\\"off\\\""),"HA climate uses the entity's own mode and fan names");
  check(failure(()->{ha.send("sensor.balcony_temperature",new JSONObject().put("on",true),new JSONObject());return null;}).contains("cannot be switched"),"HA sensors cannot be switched");
  check(!ha.adopt(new JSONObject().put("url","http://127.0.0.1:1").put("token","mac-token-0123456789abcdef")),"A connection set up on the TV wins over the Mac's");
  ha.disconnect();check(!ha.connected()&&ha.vault.read()==null,"HA disconnect removes the token");
  check(ha.adopt(new JSONObject().put("url",haURL).put("token",token).put("name","Mac's HA"))&&ha.info().getString("source").equals("mac"),"HA connection adopted from the Mac");
  check(ha.adopt(null)&&!ha.connected(),"HA connection removed on the Mac is removed here");
  check(failure(()->ha.adopt(new JSONObject().put("url","http://8.8.8.8:8123").put("token","mac-token-0123456789abcdef"))).contains("not on your home network"),"Mac cannot point the token at a public plain-HTTP server");
  if(args.length>1){ // a Home Assistant older than 2022.4: no subscribe_entities
   JSONObject legacy=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])),SharingTransport.UTF8));
   HomeAssistantClient old=new HomeAssistantClient(new MemoryStore(null));AtomicInteger oldChanges=new AtomicInteger();old.onChange=oldChanges::incrementAndGet;old.watch(Collections.singleton("switch.desk_plug"));old.connect(legacy.getString("homeassistant"),legacy.getString("token"));
   Thread.sleep(1500);int start=oldChanges.get();mock(legacy.getString("homeassistant"),"/mock/set",new JSONObject().put("id","light.living_lamp").put("state","off"));Thread.sleep(800);
   check(oldChanges.get()==start,"Legacy HA: unwatched entity changes are ignored");
   mock(legacy.getString("homeassistant"),"/mock/set",new JSONObject().put("id","switch.desk_plug").put("state","on"));check(waitFor(oldChanges,start,3000),"Legacy HA: state_changed fallback delivers watched entities");old.disconnect();
  }

  // Philips Hue
  HueClient hue=new HueClient(new MemoryStore(null));AtomicInteger hueChanges=new AtomicInteger();hue.onChange=hueChanges::incrementAndGet;
  check(failure(()->{hue.pair(hueHost,"","");return null;}).contains("round button")&&!hue.connected(),"Hue pairing waits for the link button");
  check(failure(()->{hue.pair("8.8.8.8","","");return null;}).contains("not on your home network"),"Hue pairing stays on the home network");
  mock(hueBase,"/mock/press",new JSONObject());hue.pair(hueHost,"","");
  JSONObject paired=hue.vault.read();
  check(hue.connected()&&hue.info().getString("name").equals("Mock Hue Bridge")&&hue.info().getString("bridgeId").equals("mock0bridge0001")&&!hue.info().toString().contains(paired.getString("key")),"Hue pairing names the bridge, keeps the key out of status");
  check(paired.getString("certificate").matches("[a-f0-9]{64}"),"Hue bridge certificate pinned at pairing");
  String lamp=info.getJSONObject("lights").getString("Hue color lamp"),plug=info.getJSONObject("lights").getString("Hue smart plug"),sensor=info.getString("sensor");
  hue.watch(new HashSet<>(Arrays.asList(lamp,plug,sensor)));
  Map<String,JSONObject> lights=hue.read(new HashSet<>(Arrays.asList(lamp,plug)),Collections.singleton(sensor));
  check(!lights.get(lamp).getJSONObject("light").getBoolean("on")&&Math.abs(lights.get(lamp).getJSONObject("light").getDouble("temp")-1000000/366.)<1e-6&&lights.get(sensor).getDouble("temperature")==21.3,"Hue read maps lamps and thermometer");
  Thread.sleep(1500);int hueBefore=hueChanges.get();mock(hueBase,"/mock/set",new JSONObject().put("id",plug).put("on",true));
  check(waitFor(hueChanges,hueBefore,4000),"Hue event stream reports a change made in the Hue app");
  hue.send(lamp,new JSONObject().put("on",true).put("brightness",.5).put("color","#0000ff"));
  JSONObject now=hue.read(Collections.singleton(lamp),Collections.emptySet()).get(lamp).getJSONObject("light");double[] blue=LocalMapper.rgb(now.getString("color"));
  check(now.getBoolean("on")&&Math.abs(now.getDouble("brightness")-.5)<1e-9&&blue[2]==1&&blue[0]<.2,"Hue command applied by the bridge");
  check(failure(()->{hue.send("../config",new JSONObject().put("on",true));return null;}).contains("Unknown Hue lamp"),"Hue ids are validated");
  JSONObject forged=CloudMapper.copy(paired).put("certificate","0000000000000000000000000000000000000000000000000000000000000000");
  HueClient impostor=new HueClient(new MemoryStore(forged));check(failure(()->impostor.read(Collections.singleton(lamp),Collections.emptySet())).contains("not the paired Hue Bridge"),"A different certificate at the bridge address is refused");impostor.disconnect();
  HueClient shared=new HueClient(new MemoryStore(null));check(shared.adopt(CloudMapper.copy(paired).put("certificate","")),"Hue pairing adopted from the Mac");
  shared.read(Collections.singleton(lamp),Collections.emptySet());check(shared.vault.read().getString("certificate").equals(paired.getString("certificate")),"A shared pairing without a pin is pinned on first use");shared.disconnect();
  hue.disconnect();check(!hue.connected()&&hue.vault.read()==null,"Hue disconnect removes the key");
  System.out.println(tests+" local hub tests passed (mock Home Assistant and Hue).");
  System.exit(0);
 }
}
