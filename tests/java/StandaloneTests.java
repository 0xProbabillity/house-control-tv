package dev.housecontrol.tv;
import java.util.*;
import org.json.*;
public class StandaloneTests {
 static int tests=0;
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);tests++;}
 public static void main(String[] args)throws Exception{
  JSONObject pair=new JSONObject().put("homeId","home").put("on","on-scene").put("off","off-scene"),link=new JSONObject().put("sceneSwitch",pair);
  check(CloudMapper.sceneCommand(link,new JSONObject().put("on",true)).getString("sceneId").equals("on-scene"),"IR switch on scene");
  check(CloudMapper.sceneCommand(link,new JSONObject().put("on",false)).getString("sceneId").equals("off-scene"),"IR switch off scene");
  boolean badPair=false;try{CloudMapper.sceneCommand(new JSONObject().put("sceneSwitch",new JSONObject().put("homeId","home").put("on","same").put("off","same")),new JSONObject().put("on",true));}catch(java.io.IOException e){badPair=true;}check(badPair,"Reject toggle scene as on/off pair");
  badPair=false;try{CloudMapper.sceneCommand(link,new JSONObject().put("on",true).put("brightness",.5));}catch(java.io.IOException e){badPair=true;}check(badPair,"IR switch rejects unsupported dimming");
  String rid="11111111-2222-3333-4444-555555555555",hash=SharingTransport.md5(rid+"refresh-token"),secret=SharingTransport.secret(rid,hash);
  String cipher=SharingTransport.encrypt("{\"homeId\":\"42\"}",secret,"ABCDEFGHJKMN".getBytes(SharingTransport.UTF8));
  check(SharingTransport.decrypt(cipher,secret).equals("{\"homeId\":\"42\"}"),"AES-GCM round trip");
  check(hash.equals("81542abb4375f4f3f5779f8c4889cd91"),"Reference MD5");
  check(secret.equals("a43689fcfc68604f"),"Swift CryptoKit key reference");
  check(cipher.equals("QUJDREVGR0hKS01OFU1ZSihrcsNcuGsQrbBdtDiy67jIifj11G/ygakQKg=="),"Swift CryptoKit AES-GCM reference");
  byte[] modified=Base64.getDecoder().decode(cipher);modified[modified.length-1]^=1;boolean rejected=false;try{SharingTransport.decrypt(Base64.getEncoder().encodeToString(modified),secret);}catch(javax.crypto.AEADBadTagException e){rejected=true;}check(rejected,"Reject tampered ciphertext");
  Set<String> codes=new HashSet<>(Arrays.asList("switch_led","bright_value_v2","temp_value_v2","work_mode"));
  JSONObject ranges=new JSONObject().put("bright_value_v2",new JSONObject().put("min",10).put("max",1000));
  JSONArray commands=CloudMapper.lights(new JSONObject().put("on",true).put("brightness",.5),new JSONObject(),new JSONObject(),codes,ranges);
  check(commands.getJSONObject(0).getString("code").equals("switch_led")&&commands.getJSONObject(0).getBoolean("value"),"Smart bulb power");
  check(commands.getJSONObject(1).getInt("value")==505,"Brightness specification range");
  commands=CloudMapper.lights(new JSONObject().put("on",false),new JSONObject(),new JSONObject().put("codes",new JSONObject().put("switch","custom_switch")),codes,ranges);
  check(commands.getJSONObject(0).getString("code").equals("custom_switch"),"Custom switch code");
  List<CloudMapper.Key> keys=CloudMapper.keys(new JSONObject().put("on",true).put("target",23).put("mode","cool").put("fan","high"));
  check(keys.size()==4&&keys.get(0).field.equals("on")&&keys.get(1).field.equals("mode")&&keys.get(2).field.equals("target")&&keys.get(3).field.equals("fan"),"IR command order");
  check(keys.get(0).command.getString("code").equals("PowerOn")&&keys.get(2).command.getInt("value")==23,"IR values");
  check(CloudMapper.infrared(new HashSet<>(Arrays.asList("PowerOff"))),"IR detection");
  JSONArray status=new JSONArray().put(new JSONObject().put("code","temp_current").put("value",215));check(CloudMapper.temperature(status)==21.5,"Temperature sensor scale");
  JSONObject state=CloudMapper.reading(new JSONArray().put(new JSONObject().put("code","switch_led").put("value",true)).put(new JSONObject().put("code","bright_value_v2").put("value",505)),ranges,false);
  check(state.getBoolean("on")&&Math.abs(state.getDouble("brightness")-.5)<.0001,"Light state mapping");
  check(SmartLifeClient.trustedEndpoint("https://apigw.tuyaeu.com"),"EU gateway");
  check(!SmartLifeClient.trustedEndpoint("http://apigw.tuyaeu.com")&&!SmartLifeClient.trustedEndpoint("https://apigw.tuyaeu.com.evil.example")&&!SmartLifeClient.trustedEndpoint("https://evil.example@apigw.tuyaeu.com"),"Endpoint restriction");
  JSONObject patch=CloudMapper.patch("acs",new JSONObject().put("target",99).put("mode","heat").put("roomTemp",99),new JSONObject().put("min",16).put("max",30));
  check(patch.getInt("target")==30&&patch.getString("mode").equals("heat")&&!patch.has("roomTemp"),"Scene temperature clamping and writable fields");
  patch=CloudMapper.patch("lights",new JSONObject().put("brightness",-5).put("temp",99999).put("color",JSONObject.NULL),new JSONObject());
  check(patch.getDouble("brightness")==.05&&patch.getInt("temp")==6500&&patch.isNull("color"),"Scene light ranges");
  rejected=false;try{CloudMapper.patch("acs",new JSONObject().put("mode","invalid"),new JSONObject());}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Invalid scene mode rejected");
  rejected=false;try{CloudMapper.patch("lights",new JSONObject().put("on","true"),new JSONObject());}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Scene on/off type validation");
  check(CloudMapper.difference(new JSONObject().put("on",false),new JSONObject().put("on",false)).length()==0,"Scene skips already satisfied switch");
  check(CloudMapper.difference(new JSONObject().put("on",true),new JSONObject().put("on",false)).getBoolean("on"),"Scene keeps required switch");
  check(CloudMapper.difference(new JSONObject().put("target",24),new JSONObject().put("target",24.0)).length()==0,"Scene compares numeric values");
  JSONObject example=new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("web/public/plans/demo-loft.json")),SharingTransport.UTF8));
  DemoHome demo=new DemoHome(example);JSONObject demoSnapshot=demo.action(new JSONObject().put("kind","scene").put("id","movie")).getJSONObject("home");
  check(demoSnapshot.getBoolean("demo")&&!demoSnapshot.getBoolean("realDeviceConnections"),"Demo has no real connections");
  JSONObject demoLights=demoSnapshot.getJSONObject("state").getJSONObject("lights");int lit=0;Iterator<String> lightIds=demoLights.keys();while(lightIds.hasNext())if(demoLights.getJSONObject(lightIds.next()).getBoolean("on"))lit++;
  check(lit==1&&demoLights.getJSONObject("main.tv").getBoolean("on"),"Demo Movie only lights the TV strip");
  demoLights=demo.action(new JSONObject().put("kind","scene").put("id","night")).getJSONObject("home").getJSONObject("state").getJSONObject("lights");
  check(demoLights.getJSONObject("main.bed").getBoolean("on")&&demoLights.getJSONObject("main.bed.left").getBoolean("on"),"Demo Night has both bedside lamps");
  example.getJSONArray("lights").getJSONObject(0).put("tuya",new JSONObject().put("deviceId","never-send"));rejected=false;try{new DemoHome(example);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Real bindings rejected by demo constructor");
  localMappings();
  System.out.println(tests+" standalone protocol tests passed.");
 }
 static boolean near(double a,double b,double tolerance){return Math.abs(a-b)<=tolerance;}
 static JSONObject ha(String id,String state,JSONObject attributes)throws Exception{return new JSONObject().put("entity_id",id).put("state",state).put("attributes",attributes);}
 static JSONObject callData(JSONArray calls,int i)throws Exception{return calls.getJSONObject(i).getJSONObject("data");}
 static boolean rejects(java.util.concurrent.Callable<?> c){try{c.call();return false;}catch(Exception e){return true;}}
 /** Home Assistant and Hue translations, against the upstream Swift mapping. */
 static void localMappings()throws Exception{
  JSONObject r=LocalMapper.haReading(ha("light.lamp","on",new JSONObject().put("brightness",128).put("color_mode","color_temp").put("color_temp_kelvin",3000)));
  JSONObject light=r.getJSONObject("light");check(r.getBoolean("online")&&light.getBoolean("on")&&near(light.getDouble("brightness"),128/255.,1e-6)&&light.getDouble("temp")==3000&&light.isNull("color"),"HA white light reading");
  light=LocalMapper.haReading(ha("light.lamp","on",new JSONObject().put("color_mode","hs").put("rgb_color",new JSONArray("[255,0,0]")))).getJSONObject("light");check(light.getString("color").equals("#ff0000"),"HA colour light reading");
  light=LocalMapper.haReading(ha("light.lamp","off",new JSONObject().put("brightness",200))).getJSONObject("light");check(!light.getBoolean("on")&&!light.has("brightness"),"HA off light has no brightness");
  check(!LocalMapper.haReading(ha("switch.plug","unavailable",new JSONObject())).getBoolean("online"),"HA unavailable entity is offline");
  JSONObject climate=new JSONObject().put("hvac_modes",new JSONArray("[\"off\",\"cool\",\"heat\",\"fan_only\",\"heat_cool\"]")).put("fan_modes",new JSONArray("[\"Auto\",\"Low\",\"Mid\",\"High\"]")).put("fan_mode","Mid").put("temperature",23).put("current_temperature",24.5);
  r=LocalMapper.haReading(ha("climate.ac","cool",climate));JSONObject ac=r.getJSONObject("ac");
  check(ac.getBoolean("on")&&ac.getString("mode").equals("cool")&&ac.getDouble("target")==23&&ac.getDouble("roomTemp")==24.5&&ac.getString("fan").equals("medium"),"HA climate reading");
  check(r.getJSONObject("modes").getString("fan").equals("fan_only")&&r.getJSONObject("modes").getString("auto").equals("heat_cool")&&r.getJSONObject("fans").getString("medium").equals("Mid"),"HA climate mode and fan names");
  check(!LocalMapper.haReading(ha("climate.ac","off",climate)).getJSONObject("ac").getBoolean("on"),"HA climate off");
  check(LocalMapper.haReading(ha("climate.ac","heat_cool",new JSONObject().put("hvac_modes",new JSONArray("[\"heat_cool\"]")).put("target_temp_low",20).put("target_temp_high",25))).getJSONObject("ac").getDouble("target")==23,"HA dual set-point becomes one target");
  check(near(LocalMapper.haReading(ha("sensor.t","68.0",new JSONObject().put("device_class","temperature").put("unit_of_measurement","°F"))).getDouble("temperature"),20,1e-9),"HA Fahrenheit sensor");
  JSONObject modes=r.getJSONObject("modes"),fans=r.getJSONObject("fans"),off=new JSONObject().put("on",false);
  JSONArray calls=LocalMapper.haCalls("light.lamp",off,new JSONObject(),new JSONObject(),new JSONObject());check(calls.length()==1&&calls.getJSONObject(0).getString("service").equals("turn_off")&&callData(calls,0).getString("entity_id").equals("light.lamp"),"HA light off");
  calls=LocalMapper.haCalls("light.lamp",new JSONObject().put("brightness",.5).put("temp",2700),new JSONObject(),new JSONObject(),new JSONObject());check(calls.length()==1&&callData(calls,0).getInt("brightness_pct")==50&&callData(calls,0).getInt("color_temp_kelvin")==2700,"HA light dim and white");
  calls=LocalMapper.haCalls("light.lamp",new JSONObject().put("color","#00ff00"),new JSONObject(),new JSONObject(),new JSONObject());check(callData(calls,0).getJSONArray("rgb_color").toString().equals("[0,255,0]"),"HA light colour");
  check(LocalMapper.haCalls("light.lamp",new JSONObject().put("color",JSONObject.NULL),new JSONObject(),new JSONObject(),new JSONObject()).length()==0,"HA clearing colour sends nothing");
  calls=LocalMapper.haCalls("switch.plug",new JSONObject().put("on",true),new JSONObject(),new JSONObject(),new JSONObject());check(calls.getJSONObject(0).getString("domain").equals("switch")&&calls.getJSONObject(0).getString("service").equals("turn_on"),"HA switch on");
  calls=LocalMapper.haCalls("climate.ac",off,new JSONObject(),fans,modes);check(calls.length()==1&&callData(calls,0).getString("hvac_mode").equals("off"),"HA climate off");
  calls=LocalMapper.haCalls("climate.ac",new JSONObject().put("on",true),new JSONObject().put("mode","heat"),fans,modes);check(calls.length()==1&&callData(calls,0).getString("hvac_mode").equals("heat"),"HA climate on uses the saved mode");
  calls=LocalMapper.haCalls("climate.ac",new JSONObject().put("mode","auto").put("target",22).put("fan","medium"),new JSONObject(),fans,modes);
  check(calls.length()==3&&callData(calls,0).getString("hvac_mode").equals("heat_cool")&&callData(calls,1).getDouble("temperature")==22&&callData(calls,2).getString("fan_mode").equals("Mid"),"HA climate mode, target and fan names");
  check(LocalMapper.haCalls("climate.ac",new JSONObject().put("fan","turbo"),new JSONObject(),fans,modes).length()==0,"HA unsupported fan is skipped");
  JSONObject hue=new JSONObject().put("on",new JSONObject().put("on",true)).put("dimming",new JSONObject().put("brightness",80)).put("color_temperature",new JSONObject().put("mirek",366).put("mirek_valid",true)).put("color",new JSONObject().put("xy",new JSONObject().put("x",.6401).put("y",.33)));
  light=LocalMapper.hueLight(hue);check(light.getBoolean("on")&&near(light.getDouble("brightness"),.8,1e-9)&&near(light.getDouble("temp"),1000000/366.,1e-6)&&light.isNull("color"),"Hue white reading");
  hue.getJSONObject("color_temperature").put("mirek_valid",false);light=LocalMapper.hueLight(hue);double[] red=LocalMapper.rgb(light.getString("color"));check(red[0]==1&&red[1]<.1&&red[2]<.1&&!light.has("temp"),"Hue colour reading");
  JSONObject body=LocalMapper.hueBody(new JSONObject().put("on",true).put("brightness",.5).put("temp",2700).put("color","#ff0000"));
  check(body.getJSONObject("on").getBoolean("on")&&body.getJSONObject("dimming").getDouble("brightness")==50&&body.getJSONObject("color_temperature").getInt("mirek")==370,"Hue command body");
  check(near(body.getJSONObject("color").getJSONObject("xy").getDouble("x"),.64,.001)&&near(body.getJSONObject("color").getJSONObject("xy").getDouble("y"),.33,.001),"Hue sRGB red chromaticity");
  for(String hex:new String[]{"#ff0000","#00ff00","#0000ff","#ffffff","#ff8800"}){double[] p=LocalMapper.xy(hex),a=LocalMapper.rgb(hex),b=LocalMapper.rgb(LocalMapper.hexXY(p[0],p[1]));double top=Math.max(a[0],Math.max(a[1],a[2]));check(near(a[0]/top,b[0],.02)&&near(a[1]/top,b[1],.02)&&near(a[2]/top,b[2],.02),"xy round trip "+hex);}
  check(LocalMapper.hueTemperature(new JSONObject().put("temperature",new JSONObject().put("temperature",19).put("temperature_report",new JSONObject().put("temperature",21.3))))==21.3,"Hue thermometer report");
  JSONObject dim=new JSONObject().put("brightness",.5);
  check(LocalMapper.withPower(dim,new JSONObject().put("on",true)).getBoolean("on")&&!LocalMapper.withPower(dim,new JSONObject().put("on",false)).has("on")&&!LocalMapper.withPower(off,new JSONObject().put("on",true)).getBoolean("on"),"Dimming a lit lamp also switches it on");
  check(LocalMapper.haURL("homeassistant.local").equals("http://homeassistant.local:8123")&&LocalMapper.haURL(" https://ha.example.com/ ").equals("https://ha.example.com")&&LocalMapper.haURL("192.168.1.20:8123").equals("http://192.168.1.20:8123"),"HA address normalisation");
  check(rejects(()->LocalMapper.haURL("http://ha.local:8123/api"))&&rejects(()->LocalMapper.haURL("http://user@ha.local"))&&rejects(()->LocalMapper.haURL("ftp://ha.local"))&&rejects(()->LocalMapper.haURL("")),"HA address rejects paths, credentials and other schemes");
  check(LocalMapper.hueHost("https://192.168.1.2/").equals("192.168.1.2")&&LocalMapper.hueHost("192.168.1.2:8443").equals("192.168.1.2:8443")&&rejects(()->LocalMapper.hueHost("a b"))&&rejects(()->LocalMapper.hueHost("x/../y")),"Hue address");
  check(LocalMapper.bare("[fe80::1]:443").equals("fe80::1")&&LocalMapper.bare("192.168.1.2:8443").equals("192.168.1.2")&&LocalMapper.bare("bridge.local").equals("bridge.local"),"Host without port");
  check(LocalMapper.entity("light.living_room")&&LocalMapper.entity("climate.ac_2")&&!LocalMapper.entity("light.x/../y")&&!LocalMapper.entity("automation.x")&&!LocalMapper.entity("light."),"HA entity ids are validated before use in a path");
  check(LocalMapper.hueId("0f8a6a4e-1b2c-4d3e-9f00-112233445566")&&!LocalMapper.hueId("../light")&&!LocalMapper.hueId("0F8A6A4E-1B2C-4D3E-9F00-112233445566x"),"Hue ids are validated");
  JSONObject table=LocalMapper.fanTable(Arrays.asList("off","Auto","Low","Mid","High","Turbo","quiet"));
  check(table.getString("auto").equals("Auto")&&table.getString("low").equals("Low")&&table.getString("medium").equals("Mid")&&table.getString("high").equals("High")&&table.getString("turbo").equals("Turbo")&&table.length()==5,"Fan names map to the panel's five");
  check(LocalMapper.privateAddress(java.net.InetAddress.getByName("10.0.0.1"))&&LocalMapper.privateAddress(java.net.InetAddress.getByName("192.168.1.1"))&&LocalMapper.privateAddress(java.net.InetAddress.getByName("fd00::1"))&&!LocalMapper.privateAddress(java.net.InetAddress.getByName("8.8.8.8"))&&!LocalMapper.privateAddress(java.net.InetAddress.getByName("127.0.0.1")),"Credentials stay on the home network");
  check(rejects(()->{HomeAssistantClient.allowed("http://8.8.8.8:8123");return null;})&&!rejects(()->{HomeAssistantClient.allowed("https://8.8.8.8");return null;}),"Plain-HTTP Home Assistant must be local; HTTPS may be remote");
 }
}
