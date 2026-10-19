package dev.housecontrol.tv;
import org.json.*;
import java.util.*;
/** An isolated in-memory home. This class has no cloud client, context, or device transport. */
final class DemoHome {
 final JSONObject plan,state;final String revision="demo-"+UUID.randomUUID();
 DemoHome(JSONObject source)throws Exception{
  plan=CloudMapper.copy(source);state=new JSONObject().put("lights",new JSONObject()).put("acs",new JSONObject()).put("scene",JSONObject.NULL);
  for(String section:new String[]{"lights","acs"}){JSONArray devices=plan.optJSONArray(section);if(devices==null)continue;for(int i=0;i<devices.length();i++){
   JSONObject d=devices.getJSONObject(i);for(String forbidden:new String[]{"tuya","link","sensor"})if(d.has(forbidden))throw new IllegalArgumentException("Demo homes cannot contain real-device bindings.");
   d.put("linked",false);JSONObject value=section.equals("lights")?new JSONObject().put("on",true).put("brightness",.85).put("temp",3400).put("color",d.opt("color")):new JSONObject().put("on",false).put("target",23).put("mode","cool").put("fan","auto");state.getJSONObject(section).put(d.getString("id"),value);
  }}
 }
 JSONObject home()throws Exception{return new JSONObject().put("mode","demo").put("demo",true).put("realDeviceConnections",false).put("controlReady",true).put("macOnline",true).put("plan",CloudMapper.copy(plan)).put("state",CloudMapper.copy(state)).put("revision",revision).put("lastPoll",System.currentTimeMillis()).put("cloudError","").put("source","Isolated demo home");}
 JSONObject find(String section,String id)throws Exception{JSONArray a=plan.optJSONArray(section);if(a!=null)for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").equals(id))return a.getJSONObject(i);throw new IllegalArgumentException("Unknown demo object.");}
 void change(String section,String id,JSONObject patch)throws Exception{JSONObject device=find(section,id);CloudMapper.merge(state.getJSONObject(section).getJSONObject(id),CloudMapper.patch(section,patch,device));state.put("scene",JSONObject.NULL);}
 JSONObject action(JSONObject action)throws Exception{
  String kind=action.getString("kind"),id=action.optString("id");
  if(kind.equals("light")||kind.equals("acPower"))change(kind.equals("light")?"lights":"acs",id,new JSONObject().put("on",action.get("on")));
  else if(kind.equals("lightSet")){JSONObject patch=new JSONObject();for(String key:new String[]{"on","brightness","temp","color"})if(action.has(key))patch.put(key,action.get(key));change("lights",id,patch);}
  else if(kind.equals("acTarget"))change("acs",id,new JSONObject().put("target",action.get("target")));
  else if(kind.equals("scene")||kind.equals("room")||kind.equals("allLights")){
   JSONObject scene=kind.equals("scene")?find("scenes",id):null;if(kind.equals("room"))find("rooms",id);
   for(String section:new String[]{"lights","acs"}){JSONArray devices=plan.optJSONArray(section);if(devices==null)continue;
    for(int i=0;i<devices.length();i++){JSONObject device=devices.getJSONObject(i),patch=new JSONObject();String key=device.getString("id");
     if(scene!=null){JSONObject patches=scene.optJSONObject(section);if(patches!=null){CloudMapper.merge(patch,patches.optJSONObject("*"));CloudMapper.merge(patch,patches.optJSONObject(key));}}
     else if(section.equals("lights")&&(!kind.equals("room")||device.getString("room").equals(id)))patch.put("on",action.get("on"));
     if(patch.length()>0)change(section,key,patch);
    }
   }
   if(scene!=null)state.put("scene",id);
  }else throw new IllegalArgumentException("Unsupported demo action.");
  return new JSONObject().put("ok",true).put("demo",true).put("direct",false).put("home",home());
 }
}
