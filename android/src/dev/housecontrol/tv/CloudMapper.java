package dev.housecontrol.tv;

import org.json.*;
import java.util.*;
import java.io.IOException;

/** Device mappings match the upstream House Control plan format and TuyaMapper. */
final class CloudMapper {
    static JSONObject copy(JSONObject j)throws JSONException{return new JSONObject(j.toString());}
    static void merge(JSONObject to,JSONObject from)throws JSONException{if(from==null)return;Iterator<String> keys=from.keys();while(keys.hasNext()){String k=keys.next();to.put(k,from.get(k));}}
    static JSONObject dp(String code,Object value)throws JSONException{return new JSONObject().put("code",code).put("value",value);}
    static String pick(Set<String> codes,JSONObject custom,String key,String... candidates){String override=custom.optString(key);if(!override.isEmpty())return override;for(String c:candidates)if(codes.contains(c))return c;return null;}
    static double[] range(String code,JSONObject ranges,double floor){JSONObject r=ranges.optJSONObject(code);if(r!=null&&r.optDouble("max")>r.optDouble("min"))return new double[]{r.optDouble("min"),r.optDouble("max")};return code.endsWith("_v2")?new double[]{floor>0?10:0,1000}:new double[]{floor,255};}
    static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    static JSONObject difference(JSONObject patch,JSONObject current)throws Exception{
        JSONObject out=new JSONObject();Iterator<String> keys=patch.keys();while(keys.hasNext()){String key=keys.next();Object desired=patch.get(key),old=current.opt(key);boolean same=desired.equals(old);if(desired instanceof Number&&old instanceof Number)same=Math.abs(((Number)desired).doubleValue()-((Number)old).doubleValue())<.001;if(!same)out.put(key,desired);}return out;
    }
    static JSONObject sceneCommand(JSONObject link,JSONObject patch)throws Exception{
        JSONObject pair=link.optJSONObject("sceneSwitch");
        if(pair==null||pair.optString("homeId").isEmpty()||pair.optString("on").isEmpty()||pair.optString("off").isEmpty()||pair.optString("on").equals(pair.optString("off")))throw new IOException("Link separate Smart Life ON and OFF commands for this IR light.");
        if(patch.length()!=1||!(patch.opt("on") instanceof Boolean))throw new IOException("This IR light supports on/off only.");
        return new JSONObject().put("homeId",pair.getString("homeId")).put("sceneId",pair.getString(patch.getBoolean("on")?"on":"off"));
    }
    static JSONObject patch(String section,JSONObject raw,JSONObject device)throws Exception{
        JSONObject out=new JSONObject();
        if(raw.has("on")){if(!(raw.get("on") instanceof Boolean))throw new IllegalArgumentException("Expected an explicit on/off value.");out.put("on",raw.getBoolean("on"));}
        if(section.equals("lights")){
            for(String key:new String[]{"brightness","temp"})if(raw.has(key)){Object v=raw.get(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IllegalArgumentException("Invalid light setting.");double value=((Number)v).doubleValue();out.put(key,key.equals("brightness")?clamp(value,.05,1):clamp(value,2200,6500));}
            if(raw.has("color")){if(!raw.isNull("color")&&!raw.getString("color").matches("#[a-fA-F0-9]{6}"))throw new IllegalArgumentException("Invalid light colour.");out.put("color",raw.get("color"));}
        }else{
            if(raw.has("target")){Object v=raw.get("target");if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IllegalArgumentException("Invalid temperature.");out.put("target",Math.round(clamp(((Number)v).doubleValue(),device.optInt("min",16),device.optInt("max",30))));}
            if(raw.has("mode")){String value=raw.getString("mode");if(!MODES.containsKey(value))throw new IllegalArgumentException("Unsupported climate mode.");out.put("mode",value);}
            if(raw.has("fan")){String value=raw.getString("fan");if(!FANS.containsKey(value))throw new IllegalArgumentException("Unsupported fan speed.");out.put("fan",value);}
        }
        return out;
    }
    static JSONArray lights(JSONObject patch,JSONObject state,JSONObject link,Set<String> codes,JSONObject ranges)throws Exception{
        JSONObject custom=link.optJSONObject("codes");if(custom==null)custom=new JSONObject();JSONArray out=new JSONArray();String code;
        if(patch.has("on")){code=pick(codes,custom,"switch","switch_led","switch_1","switch");out.put(dp(code==null?"switch_1":code,patch.getBoolean("on")));}
        if(patch.has("brightness")&&(code=pick(codes,custom,"brightness","bright_value_v2","bright_value"))!=null){double[] r=range(code,ranges,25);out.put(dp(code,Math.round(r[0]+clamp(patch.getDouble("brightness"),.05,1)*(r[1]-r[0]))));}
        if(patch.has("temp")&&(code=pick(codes,custom,"temp","temp_value_v2","temp_value"))!=null){double[] r=range(code,ranges,0);if(codes.contains("work_mode"))out.put(dp("work_mode","white"));out.put(dp(code,Math.round(r[0]+clamp((patch.getDouble("temp")-2200)/4300,0,1)*(r[1]-r[0]))));}
        if(patch.has("color")&&!patch.isNull("color")&&(code=pick(codes,custom,"color","colour_data_v2","colour_data"))!=null){
            String hex=patch.getString("color").replace("#","");if(!hex.matches("[a-fA-F0-9]{6}"))throw new IllegalArgumentException("Invalid light colour.");int n=Integer.parseInt(hex,16);double r=((n>>16)&255)/255.,g=((n>>8)&255)/255.,b=(n&255)/255.,hi=Math.max(r,Math.max(g,b)),lo=Math.min(r,Math.min(g,b)),delta=hi-lo,h=0;
            if(delta>0){h=(hi==r?(g-b)/delta%6:hi==g?(b-r)/delta+2:(r-g)/delta+4)*60;if(h<0)h+=360;}
            double scale=range(code.equals("colour_data_v2")?"bright_value_v2":"bright_value",ranges,0)[1]>255?1000:255;
            if(codes.contains("work_mode"))out.put(dp("work_mode","colour"));out.put(dp(code,new JSONObject().put("h",(int)h).put("s",(int)((hi==0?0:delta/hi)*scale)).put("v",(int)(Math.max(.01,state.optDouble("brightness",1))*scale)).toString()));
        }
        return out;
    }
    static boolean infrared(Set<String> codes){return codes.contains("PowerOn")||codes.contains("PowerOff");}
    static final Map<String,String> MODES=new HashMap<>(),FANS=new HashMap<>();
    static{String[] a={"cool","cold","heat","hot","dry","wet","fan","wind","auto","auto"};for(int i=0;i<a.length;i+=2)MODES.put(a[i],a[i+1]);String[] b={"auto","auto","low","low","medium","mid","high","high","turbo","strong"};for(int i=0;i<b.length;i+=2)FANS.put(b[i],b[i+1]);}
    static JSONArray air(JSONObject patch,JSONObject link,Set<String> codes)throws Exception{
        JSONArray out=new JSONArray();JSONObject custom=link.optJSONObject("codes");if(custom==null)custom=new JSONObject();String code;
        if(patch.has("on")){code=pick(codes,custom,"switch","switch","power","switch_1");out.put(dp(code==null?"switch":code,patch.getBoolean("on")));}
        if(patch.has("target")){code=pick(codes,custom,"target","temp_set");out.put(dp(code==null?"temp_set":code,patch.getInt("target")));}
        if(patch.has("mode"))out.put(dp("mode",MODES.get(patch.getString("mode"))));
        if(patch.has("fan")&&(code=pick(codes,custom,"fan","fan_speed_enum","windspeed"))!=null)out.put(dp(code,FANS.get(patch.getString("fan"))));return out;
    }
    static final class Key{final String field;final JSONObject command;Key(String f,String code,Object value)throws Exception{field=f;command=dp(code,value);}}
    static List<Key> keys(JSONObject patch)throws Exception{
        List<Key> result=new ArrayList<>();if(patch.has("on")){String c=patch.getBoolean("on")?"PowerOn":"PowerOff";result.add(new Key("on",c,c));}
        if(patch.has("mode")){int m=Arrays.asList("cool","heat","auto","fan","dry").indexOf(patch.getString("mode"));if(m>=0)result.add(new Key("mode","M",m));}
        if(patch.has("target"))result.add(new Key("target","T",patch.getInt("target")));
        if(patch.has("fan")){int f=Arrays.asList("auto","low","medium","high","turbo").indexOf(patch.getString("fan"));if(f>=0)result.add(new Key("fan","F",Math.min(3,f)));}return result;
    }
    static JSONObject reading(JSONArray status,JSONObject ranges,boolean ac)throws Exception{
        JSONObject out=new JSONObject();for(int i=0;i<status.length();i++){JSONObject item=status.getJSONObject(i);String code=item.optString("code");Object v=item.opt("value");
            if(!ac){if(Arrays.asList("switch_led","switch_1","switch").contains(code)&&v instanceof Boolean&&!out.has("on"))out.put("on",v);
                if(v instanceof Number&&(code.equals("bright_value_v2")||code.equals("bright_value"))){double[] r=range(code,ranges,25);out.put("brightness",clamp((((Number)v).doubleValue()-r[0])/(r[1]-r[0]),.05,1));}
                if(v instanceof Number&&(code.equals("temp_value_v2")||code.equals("temp_value"))){double[] r=range(code,ranges,0);out.put("temp",2200+clamp((((Number)v).doubleValue()-r[0])/(r[1]-r[0]),0,1)*4300);}
            }else{if((code.equals("switch")||code.equals("power"))&&v instanceof Boolean)out.put("on",v);if(code.equals("temp_set")&&v instanceof Number)out.put("target",v);if(code.equals("temp_current")&&v instanceof Number)out.put("roomTemp",v);
                if(code.equals("mode"))for(String m:MODES.keySet())if(MODES.get(m).equals(v))out.put("mode",m);
                if(code.equals("fan_speed_enum")||code.equals("windspeed"))for(String f:FANS.keySet())if(FANS.get(f).equals(v))out.put("fan",f);
            }
        }return out;
    }
    static Double temperature(JSONArray status)throws Exception{if(status==null)return null;for(int i=0;i<status.length();i++){JSONObject d=status.getJSONObject(i);if(Arrays.asList("temp_current","va_temperature").contains(d.optString("code"))&&d.opt("value") instanceof Number){double t=d.getDouble("value");return Math.abs(t)>60?t/10:t;}}return null;}
}
