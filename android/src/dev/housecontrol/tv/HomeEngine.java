package dev.housecontrol.tv;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable TV-owned plan/state and direct device commands (Smart Life, Home Assistant, Hue). Mac is not in the runtime path. */
final class HomeEngine {
    final Context context;
    final Object lock=new Object(),actionLock=new Object();
    final SmartLifeClient cloud;
    final HomeAssistantClient ha;
    final HueClient hue;
    JSONObject plan,state;
    DemoHome demoHome;
    String revision="",userCode="",lastError="",loginToken="",loginCode="",syncFingerprint="";
    long loginStarted=0,lastPoll=0;
    volatile boolean polling=false;
    private boolean activityActive=false,widgetActive=false;
    final Map<String,Long> lastKeys=new HashMap<>();
    final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    // Home Assistant and Hue answer on the LAN in milliseconds: their reads never wait behind a slow cloud poll.
    final ScheduledExecutorService localScheduler=Executors.newSingleThreadScheduledExecutor();
    final java.util.concurrent.atomic.AtomicBoolean localPending=new java.util.concurrent.atomic.AtomicBoolean();
    /** When each plan device last received a command: an older read must not undo it. */
    final Map<String,Long> touched=new ConcurrentHashMap<>();
    HomeEngine(Context c)throws Exception{
        context=c.getApplicationContext();cloud=new SmartLifeClient(new SecureVault(context));
        ha=new HomeAssistantClient(new SecureVault(context,"homeassistant.enc"));hue=new HueClient(new SecureVault(context,"hue.enc"));
        ha.onChange=this::localChanged;hue.onChange=this::localChanged;ha.active=()->polling;hue.active=()->polling;
        if(context.getSharedPreferences("presentation",0).getBoolean("demo",false))loadDemo();
        JSONObject saved=read("home.json");if(saved!=null){plan=saved.getJSONObject("plan");state=saved.getJSONObject("state");revision=saved.optString("revision");userCode=saved.optString("userCode");syncFingerprint=saved.optString("syncFingerprint");}
        watchLinks();
        scheduler.scheduleWithFixedDelay(()->{if(polling&&cloud.connected())try{poll();}catch(Exception e){synchronized(lock){lastError=safe(e);}}},1,12,TimeUnit.SECONDS);
        localScheduler.scheduleWithFixedDelay(()->{if(polling&&(ha.connected()||hue.connected()))try{pollLocal();}catch(Exception ignored){}},2,5,TimeUnit.SECONDS);
    }
    static String safe(Exception e){return e.getMessage()==null?"Device request failed.":e.getMessage();}
    void loadDemo()throws Exception{String name=context.getSharedPreferences("presentation",0).getString("demoPlan","demo-loft");if(!name.matches("[a-z-]{1,24}"))name="demo-loft";try(InputStream in=context.getAssets().open("web/plans/"+name+".json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);demoHome=new DemoHome(new JSONObject(new String(out.toByteArray(),SharingTransport.UTF8)));}}
    JSONObject setDemo(JSONObject body)throws Exception{synchronized(actionLock){synchronized(lock){if(body.has("plan"))context.getSharedPreferences("presentation",0).edit().putString("demoPlan",body.optString("plan","demo-loft")).commit();if(body.optBoolean("enabled"))loadDemo();else demoHome=null;context.getSharedPreferences("presentation",0).edit().putBoolean("demo",demoHome!=null).commit();return home();}}}
    synchronized void setActive(boolean on){activityActive=on;polling=activityActive||widgetActive;}
    synchronized void setWidgetActive(boolean on){widgetActive=on;polling=activityActive||widgetActive;}
    JSONObject read(String name)throws Exception{AtomicFile f=new AtomicFile(new File(context.getFilesDir(),name));return f.getBaseFile().exists()?new JSONObject(new String(f.readFully(),SharingTransport.UTF8)):null;}
    void write(String name,JSONObject value)throws Exception{AtomicFile f=new AtomicFile(new File(context.getFilesDir(),name));FileOutputStream out=null;try{out=f.startWrite();out.write(value.toString().getBytes(SharingTransport.UTF8));f.finishWrite(out);}catch(Exception e){f.failWrite(out);throw e;}}
    void save()throws Exception{write("home.json",new JSONObject().put("plan",plan).put("state",state).put("revision",revision).put("userCode",userCode).put("syncFingerprint",syncFingerprint));}
    static void validatePlan(JSONObject p)throws Exception{
        JSONArray rooms=p.getJSONArray("rooms");if(rooms.length()<1||rooms.length()>100)throw new IOException("A home needs 1–100 rooms.");Set<String> ids=new HashSet<>(),roomIds=new HashSet<>();
        for(int i=0;i<rooms.length();i++){JSONObject r=rooms.getJSONObject(i);String id=r.getString("id");JSONArray rect=r.getJSONArray("rect");if(id.isEmpty()||!ids.add(id)||rect.length()!=4)throw new IOException("Invalid room id or rectangle.");roomIds.add(id);for(int n=0;n<4;n++)if(!Double.isFinite(rect.getDouble(n))||Math.abs(rect.getDouble(n))>500)throw new IOException("Invalid room dimensions.");if(rect.getDouble(2)<=0||rect.getDouble(3)<=0)throw new IOException("Room size must be positive.");}
        for(String section:new String[]{"lights","acs"}){JSONArray a=p.optJSONArray(section);if(a==null)continue;if(a.length()>200)throw new IOException("Too many devices.");for(int i=0;i<a.length();i++){JSONObject d=a.getJSONObject(i);if(!ids.add(d.getString("id"))||!roomIds.contains(d.getString("room")))throw new IOException("Duplicate device id or unknown room.");JSONArray pos=d.getJSONArray("pos");if(pos.length()!=2||!Double.isFinite(pos.getDouble(0))||!Double.isFinite(pos.getDouble(1)))throw new IOException("Invalid device position.");}}
    }
    void importHome(JSONObject data)throws Exception{
        JSONObject p=data.has("plan")?data.getJSONObject("plan"):data;validatePlan(p);JSONObject incoming=data.optJSONObject("state"),next=new JSONObject().put("lights",new JSONObject()).put("acs",new JSONObject());
        synchronized(actionLock){synchronized(lock){
            String fingerprint=data.optString("syncFingerprint");boolean settingsOnly=data.optBoolean("settingsOnly");
            if(settingsOnly&&!fingerprint.matches("[a-f0-9]{64}"))throw new IOException("Invalid settings fingerprint.");
            if(settingsOnly&&plan!=null&&fingerprint.equals(syncFingerprint))return;
            if(settingsOnly)incoming=null;
            for(String section:new String[]{"lights","acs"}){JSONArray devices=p.optJSONArray(section);if(devices==null)continue;for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);String id=d.getString("id");JSONObject value=new JSONObject();
                if(section.equals("lights"))value.put("on",false).put("brightness",.8).put("temp",3000).put("color",JSONObject.NULL);else value.put("on",false).put("target",24).put("mode","cool").put("fan","auto");
                if(state!=null&&state.optJSONObject(section)!=null)CloudMapper.merge(value,state.getJSONObject(section).optJSONObject(id));
                if(incoming!=null&&incoming.optJSONObject(section)!=null)CloudMapper.merge(value,incoming.getJSONObject(section).optJSONObject(id));next.getJSONObject(section).put(id,value);
            }}
            Object selected=settingsOnly&&state!=null?state.opt("scene"):incoming==null?JSONObject.NULL:incoming.opt("scene");
            if(selected instanceof String){boolean exists=false;JSONArray scenes=p.optJSONArray("scenes");if(scenes!=null)for(int i=0;i<scenes.length();i++)if(scenes.getJSONObject(i).optString("id").equals(selected))exists=true;if(!exists)selected=JSONObject.NULL;}
            next.put("scene",selected);plan=CloudMapper.copy(p);state=next;revision=UUID.randomUUID().toString();userCode=data.optString("userCode",userCode);syncFingerprint=settingsOnly?fingerprint:"";save();
        }}
        watchLinks();localChanged();
    }
    JSONObject home()throws Exception{
        synchronized(lock){if(demoHome!=null)return demoHome.home().put("realCommandAttempts",cloud.commandAttempts).put("syncFingerprint",syncFingerprint);if(plan==null)throw new IOException("Import your home from Mac once, or upload a floor plan from your phone.");
            JSONObject safePlan=CloudMapper.copy(plan);Set<String> needed=new TreeSet<>();
            for(String section:new String[]{"lights","acs"}){JSONArray a=safePlan.optJSONArray(section);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject d=a.getJSONObject(i);String via=via(d);boolean real=!via.isEmpty();if(real){d.put("via",via);if(!via.equals("homekit"))needed.add(via);}d.put("linked",real);d.remove("tuya");d.remove("link");d.remove("sensor");}}
            List<String> on=new ArrayList<>(),off=new ArrayList<>();for(String s:needed)(serviceReady(s)?on:off).add(title(s));
            boolean ready=needed.isEmpty()||!on.isEmpty();String label=off.isEmpty()?"TV · "+(on.isEmpty()?"Smart Life":String.join(", ",on))+" connected":on.isEmpty()?"Connect "+String.join(", ",off)+" on TV":"TV · "+String.join(", ",on)+" · connect "+String.join(", ",off);
            JSONObject errors=new JSONObject().put("smartLife",lastError).put("homeassistant",ha.lastError).put("hue",hue.lastError),services=new JSONObject().put("smartLife",cloud.connected()).put("homeassistant",ha.connected()).put("hue",hue.connected());
            return new JSONObject().put("version",2).put("mode","standalone").put("plan",safePlan).put("state",CloudMapper.copy(state)).put("revision",revision).put("syncFingerprint",syncFingerprint).put("macOnline",ready).put("controlReady",ready).put("smartLifeConnected",cloud.connected()).put("connections",services).put("connectionErrors",errors).put("connectionLabel",label).put("lastPoll",lastPoll).put("cloudError",lastError).put("source","This TV");
        }
    }
    /** Which platform switches this plan device: a `link` to another platform wins over Smart Life, as on the Mac. */
    static String via(JSONObject d){JSONObject l=d.optJSONObject("link");if(l!=null&&!l.optString("id").isEmpty())return l.optString("service");return d.optJSONObject("tuya")!=null?"tuya":"";}
    boolean serviceReady(String s){return s.equals("tuya")?cloud.connected():s.equals(LocalMapper.HA)?ha.connected():s.equals(LocalMapper.HUE)&&hue.connected();}
    static String title(String s){return s.equals("tuya")?"Smart Life":s.equals(LocalMapper.HA)?"Home Assistant":s.equals(LocalMapper.HUE)?"Hue":s.equals("homekit")?"Apple Home":s;}
    JSONObject authStatus()throws Exception{return new JSONObject().put("connected",cloud.connected()).put("userCode",userCode).put("pending",!loginToken.isEmpty()).put("lastError",lastError);}
    JSONObject authStart(JSONObject body)throws Exception{
        String code=body.optString("userCode",userCode).trim();JSONObject ticket=cloud.begin(code);loginToken=ticket.getString("loginToken");loginCode=code;loginStarted=System.currentTimeMillis();
        return new JSONObject().put("qrContent",ticket.getString("qrContent")).put("expiresIn",120);
    }
    JSONObject authFinish()throws Exception{
        if(!loginToken.isEmpty()&&System.currentTimeMillis()-loginStarted<180000){if(cloud.finish(loginToken,loginCode)){userCode=loginCode;loginToken="";lastError="";synchronized(lock){if(plan!=null)save();}scheduler.execute(()->{try{poll();}catch(Exception e){lastError=safe(e);}});}}
        else {loginToken="";throw new IOException("This QR code expired. Choose New QR code.");}return authStatus();
    }
    JSONObject find(String section,String id)throws Exception{JSONArray a=plan.optJSONArray(section);if(a!=null)for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return a.getJSONObject(i);throw new IOException("Device or scene no longer exists.");}
    void commit(String section,String id,JSONObject patch)throws Exception{synchronized(lock){CloudMapper.merge(state.getJSONObject(section).getJSONObject(id),patch);state.put("scene",JSONObject.NULL);save();}}
    void change(String section,String id,JSONObject patch)throws Exception{
        JSONObject d,current,link,desired; synchronized(lock){d=CloudMapper.copy(find(section,id));current=CloudMapper.copy(state.getJSONObject(section).getJSONObject(id));}
        patch=CloudMapper.patch(section,patch,d);if(patch.length()==0)return;
        desired=CloudMapper.copy(current);CloudMapper.merge(desired,patch);link=d.optJSONObject("tuya");
        JSONObject service=d.optJSONObject("link");if(service!=null&&!service.optString("id").isEmpty()){local(section,id,service,patch,desired);return;}
        if(link==null){commit(section,id,patch);return;}
        String device=link.getString("deviceId");if(!cloud.connected())throw new IOException("Connect Smart Life on this TV first.");
        if(section.equals("lights")&&link.has("sceneSwitch")){
            cloud.triggerScene(CloudMapper.sceneCommand(link,patch));commit(section,id,patch);return;
        }
        cloud.prepare(device);Set<String> codes=cloud.codes.getOrDefault(device,Collections.emptySet());
        if(section.equals("acs")&&CloudMapper.infrared(codes)){
            if(!desired.optBoolean("on")&&!patch.has("on")){commit(section,id,patch);return;}
            JSONObject wanted=CloudMapper.copy(patch);if(patch.optBoolean("on")){for(String key:new String[]{"mode","target","fan"})wanted.put(key,desired.opt(key));}
            if(patch.has("on")&&!patch.optBoolean("on"))wanted=new JSONObject().put("on",false);
            List<CloudMapper.Key> keys=CloudMapper.keys(wanted);boolean replayed=false;
            for(CloudMapper.Key key:keys){try{press(device,key.command);}catch(SmartLifeClient.Failure error){
                if(!error.code.equals("2008")||replayed||!desired.optBoolean("on")||key.field.equals("on"))throw error;replayed=true;
                JSONObject rest=CloudMapper.copy(desired);rest.remove(key.field);for(CloudMapper.Key other:CloudMapper.keys(rest))try{press(device,other.command);}catch(Exception ignored){}press(device,key.command);
            }commit(section,id,new JSONObject().put(key.field,wanted.opt(key.field)));}
        }else{
            JSONArray commands=section.equals("lights")?CloudMapper.lights(patch,desired,link,codes,cloud.ranges.getOrDefault(device,new JSONObject())):CloudMapper.air(patch,link,codes);
            cloud.send(device,commands);commit(section,id,patch);
        }
    }
    void local(String section,String id,JSONObject link,JSONObject patch,JSONObject desired)throws Exception{
        String service=link.optString("service"),target=link.optString("id");
        if(service.equals(LocalMapper.HUE)){if(!section.equals("lights"))throw new IOException("Hue has no air conditioners.");if(!hue.connected())throw new IOException("Pair the Hue Bridge on this TV first: Sync & connect → Home Assistant & Hue.");hue.send(target,LocalMapper.withPower(patch,desired));}
        else if(service.equals(LocalMapper.HA)){if(!ha.connected())throw new IOException("Connect Home Assistant on this TV first: Sync & connect → Home Assistant & Hue.");ha.send(target,section.equals("lights")?LocalMapper.withPower(patch,desired):patch,desired);}
        else if(service.equals("homekit"))throw new IOException("Apple Home devices can only be switched from an iPhone or iPad: HomeKit is Apple-only. Add the device to Home Assistant to control it from the TV.");
        else throw new IOException("This device uses a connection the TV does not know.");
        touched.put(id,System.currentTimeMillis());commit(section,id,patch);
    }
    void press(String id,JSONObject command)throws Exception{long wait=1000-(System.currentTimeMillis()-lastKeys.getOrDefault(id,0L));if(wait>0)Thread.sleep(wait);try{cloud.send(id,new JSONArray().put(command));}finally{lastKeys.put(id,System.currentTimeMillis());}}
    JSONObject demoAction(JSONObject body)throws Exception{synchronized(actionLock){synchronized(lock){if(demoHome==null)throw new IOException("Demo home is not active. No real-device command was sent.");}return action(body);}}
    JSONObject action(JSONObject action)throws Exception{
        synchronized(actionLock){synchronized(lock){if(demoHome!=null){JSONObject result=demoHome.action(action);return result.put("home",home());}}String kind=action.getString("kind"),id=action.optString("id");
            if(kind.equals("light")||kind.equals("acPower")){Object value=action.get("on");if(!(value instanceof Boolean))throw new IOException("Expected an explicit on/off value.");change(kind.equals("light")?"lights":"acs",id,new JSONObject().put("on",value));}
            else if(kind.equals("lightSet")){JSONObject patch=new JSONObject();for(String key:new String[]{"on","brightness","temp","color"})if(action.has(key))patch.put(key,action.get(key));if(patch.length()==0)throw new IOException("Nothing to change.");change("lights",id,patch);}
            else if(kind.equals("acTarget")){JSONObject d; synchronized(lock){d=find("acs",id);}Object value=action.get("target");if(!(value instanceof Number))throw new IOException("Invalid temperature.");double target=((Number)value).doubleValue();if(!Double.isFinite(target)||target<d.optInt("min",16)||target>d.optInt("max",30))throw new IOException("Temperature is outside this unit’s range.");change("acs",id,new JSONObject().put("target",Math.round(target)));}
            else if(kind.equals("scene")||kind.equals("allLights")||kind.equals("room")){
                if(kind.equals("scene")&&cloud.connected())poll();
                if(kind.equals("scene")&&(ha.connected()||hue.connected()))pollLocal();
                JSONObject scene=null; synchronized(lock){if(kind.equals("scene"))scene=CloudMapper.copy(find("scenes",id));else if(kind.equals("room"))find("rooms",id);}
                List<String> errors=new ArrayList<>();for(String section:new String[]{"lights","acs"}){JSONArray devices; synchronized(lock){devices=new JSONArray(plan.optJSONArray(section)==null?"[]":plan.getJSONArray(section).toString());}
                    for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i),patch=new JSONObject();String deviceId=d.getString("id");
                        if(scene!=null){JSONObject patches=scene.optJSONObject(section);if(patches!=null){CloudMapper.merge(patch,patches.optJSONObject("*"));CloudMapper.merge(patch,patches.optJSONObject(deviceId));}}
                        else if(section.equals("lights")&&(!kind.equals("room")||d.getString("room").equals(id))){if(!(action.opt("on") instanceof Boolean))throw new IOException("Expected on/off value.");patch.put("on",action.getBoolean("on"));}
                        if(patch.length()==0)continue;try{if(scene!=null&&section.equals("lights")&&!(d.optJSONObject("tuya")!=null&&d.getJSONObject("tuya").has("sceneSwitch")))synchronized(lock){patch=CloudMapper.difference(CloudMapper.patch(section,patch,d),state.getJSONObject(section).getJSONObject(deviceId));}if(patch.length()>0)change(section,deviceId,patch);}catch(Exception e){errors.add(d.optString("name",deviceId)+": "+safe(e));}
                    }}
                if(!errors.isEmpty())throw new IOException(String.join("; ",errors));if(scene!=null)synchronized(lock){state.put("scene",id);save();}
            }else throw new IOException("Unsupported TV action.");
            return new JSONObject().put("ok",true).put("direct",true).put("home",home());
        }
    }
    void poll()throws Exception{
        if(!cloud.connected())return;JSONObject p; synchronized(lock){if(demoHome!=null||plan==null)return;p=CloudMapper.copy(plan);}
        Set<String> ids=new HashSet<>();for(String section:new String[]{"lights","acs"}){JSONArray a=p.optJSONArray(section);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject l=a.getJSONObject(i).optJSONObject("tuya");if(l!=null&&via(a.getJSONObject(i)).equals("tuya")){ids.add(l.getString("deviceId"));if(!l.optString("sensorId").isEmpty())ids.add(l.getString("sensorId"));}}}
        // Poll and commands share this lock so a stale poll cannot overwrite a new command.
        synchronized(actionLock){Map<String,JSONArray> readings=cloud.status(ids);synchronized(lock){String before=state.toString();
            for(String section:new String[]{"lights","acs"}){JSONArray a=p.optJSONArray(section);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject d=a.getJSONObject(i),l=d.optJSONObject("tuya");if(l==null||l.has("sceneSwitch")||!via(d).equals("tuya"))continue;String device=l.getString("deviceId"),id=d.getString("id");JSONObject target=state.getJSONObject(section).optJSONObject(id);if(target==null)continue;
                JSONArray data=readings.get(device);if(data!=null)CloudMapper.merge(target,CloudMapper.reading(data,cloud.ranges.getOrDefault(device,new JSONObject()),section.equals("acs")));
                if(section.equals("acs")){Double t=CloudMapper.temperature(readings.get(l.optString("sensorId")));if(t!=null)target.put("roomTemp",t);}
            }}lastPoll=System.currentTimeMillis();lastError="";if(!before.equals(state.toString()))save();
        }}
    }
    /** Tells Home Assistant and Hue which of their devices the plan uses, for their live updates. */
    void watchLinks(){
        Set<String> haIds=new HashSet<>(),hueIds=new HashSet<>();JSONObject p;synchronized(lock){p=plan;}if(p!=null)for(String section:new String[]{"lights","acs"}){JSONArray a=p.optJSONArray(section);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject d=a.optJSONObject(i);if(d==null)continue;
            for(JSONObject l:new JSONObject[]{d.optJSONObject("link"),section.equals("acs")?d.optJSONObject("sensor"):null}){if(l==null||l.optString("id").isEmpty())continue;if(l.optString("service").equals(LocalMapper.HA))haIds.add(l.optString("id"));if(l.optString("service").equals(LocalMapper.HUE))hueIds.add(l.optString("id"));}}}
        ha.watch(haIds);hue.watch(hueIds);
    }
    /** A burst of events (a scene switching six lamps) becomes one read. */
    void localChanged(){if(localPending.compareAndSet(false,true))localScheduler.schedule(()->{localPending.set(false);try{pollLocal();}catch(Exception ignored){}},300,TimeUnit.MILLISECONDS);}
    void pollLocal()throws Exception{
        JSONObject p;synchronized(lock){if(demoHome!=null||plan==null)return;p=CloudMapper.copy(plan);}
        Set<String> haIds=new HashSet<>(),hueLights=new HashSet<>(),hueSensors=new HashSet<>();
        for(String section:new String[]{"lights","acs"}){JSONArray a=p.optJSONArray(section);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject d=a.getJSONObject(i),l=d.optJSONObject("link"),s=section.equals("acs")?d.optJSONObject("sensor"):null;
            if(l!=null&&!l.optString("id").isEmpty()){if(l.optString("service").equals(LocalMapper.HA))haIds.add(l.getString("id"));else if(l.optString("service").equals(LocalMapper.HUE)&&section.equals("lights"))hueLights.add(l.getString("id"));}
            if(s!=null&&!s.optString("id").isEmpty()){if(s.optString("service").equals(LocalMapper.HA))haIds.add(s.getString("id"));else if(s.optString("service").equals(LocalMapper.HUE))hueSensors.add(s.getString("id"));}}}
        if(haIds.isEmpty()&&hueLights.isEmpty()&&hueSensors.isEmpty())return;
        long started=System.currentTimeMillis();Map<String,JSONObject> fromHa=Collections.emptyMap(),fromHue=Collections.emptyMap();
        if(ha.connected()&&!haIds.isEmpty())try{fromHa=ha.read(haIds);ha.lastError="";}catch(Exception e){ha.lastError=safe(e);}
        if(hue.connected()&&!(hueLights.isEmpty()&&hueSensors.isEmpty()))try{fromHue=hue.read(hueLights,hueSensors);hue.lastError="";}catch(Exception e){hue.lastError=safe(e);}
        synchronized(lock){if(demoHome!=null||plan==null)return;String before=state.toString();
            for(String section:new String[]{"lights","acs"}){JSONArray a=p.optJSONArray(section);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject d=a.getJSONObject(i);String id=d.getString("id");JSONObject target=state.getJSONObject(section).optJSONObject(id);
                if(target==null||touched.getOrDefault(id,0L)>=started)continue;
                JSONObject l=d.optJSONObject("link"),s=section.equals("acs")?d.optJSONObject("sensor"):null;
                if(l!=null&&!l.optString("id").isEmpty()){Map<String,JSONObject> from=l.optString("service").equals(LocalMapper.HA)?fromHa:l.optString("service").equals(LocalMapper.HUE)?fromHue:Collections.emptyMap();JSONObject r=from.get(l.getString("id"));
                    if(r!=null&&r.optBoolean("online",true))CloudMapper.merge(target,r.optJSONObject(section.equals("lights")?"light":"ac"));}
                if(s!=null){Map<String,JSONObject> from=s.optString("service").equals(LocalMapper.HA)?fromHa:s.optString("service").equals(LocalMapper.HUE)?fromHue:Collections.emptyMap();JSONObject r=from.get(s.optString("id"));if(r!=null&&r.has("temperature"))target.put("roomTemp",Math.round(r.getDouble("temperature")*10)/10.);}
            }}
            lastPoll=System.currentTimeMillis();if(!before.equals(state.toString()))save();
        }
    }
    JSONObject connections()throws Exception{return new JSONObject().put("smartLife",cloud.connected()).put("homeassistant",ha.info()).put("hue",hue.info()).put("macFingerprint",context.getSharedPreferences("connections",0).getString("macFingerprint",""));}
    /** The paired Mac's Home Assistant and Hue connections (sync_mac.py). A connection set up on the TV itself is kept. */
    JSONObject adopt(JSONObject body)throws Exception{
        String fingerprint=body.optString("fingerprint");if(!fingerprint.matches("[a-f0-9]{64}"))throw new IOException("Invalid connections fingerprint.");
        if(body.has(LocalMapper.HA))ha.adopt(body.optJSONObject(LocalMapper.HA));if(body.has(LocalMapper.HUE))hue.adopt(body.optJSONObject(LocalMapper.HUE));
        context.getSharedPreferences("connections",0).edit().putString("macFingerprint",fingerprint).commit();watchLinks();localChanged();return connections();
    }
    JSONObject handle(String path,JSONObject body)throws Exception{
        switch(path){
            case "/api/connections":return connections();
            case "/api/connections/discover":return LanDiscovery.find(context);
            case "/api/connections/adopt":return adopt(body);
            case "/api/homeassistant/connect":ha.connect(body.optString("url"),body.optString("token"));watchLinks();localChanged();return connections();
            case "/api/homeassistant/disconnect":ha.disconnect();return connections();
            case "/api/hue/pair":try{hue.pair(body.optString("host"),body.optString("bridgeId"),body.optString("name"));}catch(HueClient.ButtonNotPressed e){return connections().put("buttonRequired",true);}watchLinks();localChanged();return connections();
            case "/api/hue/disconnect":hue.disconnect();return connections();case "/api/demo/action":return demoAction(body);case "/api/demo":return setDemo(body);case "/api/home":return home().put("appVersion",version());case "/api/action":return action(body);case "/api/auth/status":return authStatus();case "/api/auth/start":return authStart(body);case "/api/auth/check":return authFinish();case "/api/auth/logout":cloud.signOut();return authStatus();case "/api/import":importHome(body);return new JSONObject().put("ok",true).put("home",home());default:throw new IOException("Unknown request.");}
    }
    String version(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception e){return "";}}
    void close(){scheduler.shutdownNow();localScheduler.shutdownNow();}
}
