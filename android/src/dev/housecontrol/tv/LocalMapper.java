package dev.housecontrol.tv;

import org.json.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Home Assistant and Philips Hue translations, ported from the upstream HomeAssistantService/HueService. No I/O. */
final class LocalMapper {
    static final String HA="homeassistant",HUE="hue";
    // --- Colour (upstream ColorMath) ---
    static double[] rgb(String hex){if(hex==null)return null;String s=hex.trim().replace("#","");if(!s.matches("[a-fA-F0-9]{6}"))return null;int v=Integer.parseInt(s,16);return new double[]{((v>>16)&255)/255.,((v>>8)&255)/255.,(v&255)/255.};}
    static String hex(double r,double g,double b){return String.format(Locale.ROOT,"#%02x%02x%02x",(int)Math.round(CloudMapper.clamp(r,0,1)*255),(int)Math.round(CloudMapper.clamp(g,0,1)*255),(int)Math.round(CloudMapper.clamp(b,0,1)*255));}
    static double linear(double c){return c>.04045?Math.pow((c+.055)/1.055,2.4):c/12.92;}
    static double gamma(double c){return c<=.0031308?12.92*c:1.055*Math.pow(c,1/2.4)-.055;}
    /** CIE 1931 xy of an sRGB colour (Philips' wide-gamut conversion). */
    static double[] xy(String hex){double[] c=rgb(hex);if(c==null)return null;double r=linear(c[0]),g=linear(c[1]),b=linear(c[2]);
        double X=r*.4124+g*.3576+b*.1805,Y=r*.2126+g*.7152+b*.0722,Z=r*.0193+g*.1192+b*.9505,sum=X+Y+Z;if(sum<=0)return new double[]{.3127,.329};
        return new double[]{Math.round(X/sum*10000)/10000.,Math.round(Y/sum*10000)/10000.};}
    static String hexXY(double x,double y){if(y<=0)return "#ffffff";double Y=1,X=Y/y*x,Z=Y/y*(1-x-y);double r=X*3.2406-Y*1.5372-Z*.4986,g=-X*.9689+Y*1.8758+Z*.0415,b=X*.0557-Y*.204+Z*1.057,top=Math.max(Math.max(r,g),Math.max(b,1e-9));
        return hex(gamma(Math.max(0,r/top)),gamma(Math.max(0,g/top)),gamma(Math.max(0,b/top)));}
    static double mired(double kelvin){return 1000000/Math.max(1000,kelvin);}
    static double kelvin(double mired){return 1000000/Math.max(1,mired);}
    static Double number(JSONObject o,String key){if(o==null)return null;Object v=o.opt(key);if(v instanceof Number&&Double.isFinite(((Number)v).doubleValue()))return ((Number)v).doubleValue();if(v instanceof String)try{double d=Double.parseDouble((String)v);return Double.isFinite(d)?d:null;}catch(NumberFormatException ignored){}return null;}
    static List<String> strings(JSONArray a){List<String> out=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++)if(a.opt(i) instanceof String)out.add(a.optString(i));return out;}
    /** A lamp shown as on that gets a new brightness or colour is switched on too: the real one may be off since the last read. */
    static JSONObject withPower(JSONObject patch,JSONObject state)throws JSONException{
        if(patch.has("on")||!state.optBoolean("on")||!(patch.has("brightness")||patch.has("temp")||patch.has("color")))return patch;JSONObject out=CloudMapper.copy(patch);out.put("on",true);return out;}

    // --- Fan speeds (upstream FanNames) ---
    static final String[] FAN_ORDER={"auto","low","medium","high","turbo"};
    static String panelFan(String name){String n=name.toLowerCase(Locale.ROOT);for(String t:new String[]{"turbo","max","strong","highest","powerful","boost","very high"})if(n.contains(t))return "turbo";
        if(n.contains("high"))return "high";if(n.contains("med")||n.contains("mid"))return "medium";if(n.contains("low")||n.contains("quiet")||n.contains("silent"))return "low";if(n.contains("auto"))return "auto";return null;}
    static JSONObject fanTable(List<String> names)throws JSONException{JSONObject out=new JSONObject();for(String name:names){if(name.equalsIgnoreCase("off"))continue;String p=panelFan(name);if(p!=null&&!out.has(p))out.put(p,name);}return out;}

    // --- Home Assistant ---
    static boolean entity(String id){return id!=null&&id.matches("(light|switch|climate|sensor)\\.[a-z0-9_]{1,200}");}
    static JSONObject modeTable(List<String> hvac)throws JSONException{JSONObject out=new JSONObject();String[][] pairs={{"cool","cool"},{"heat","heat"},{"dry","dry"},{"fan","fan_only"},{"auto","auto","heat_cool"}};
        for(String[] p:pairs)for(int i=1;i<p.length;i++)if(hvac.contains(p[i])){out.put(p[0],p[i]);break;}return out;}
    static boolean isTemperature(JSONObject attributes){return "temperature".equals(attributes.optString("device_class"))||Arrays.asList("°C","°F").contains(attributes.optString("unit_of_measurement"));}
    /** One /api/states entry as {online, light|ac|temperature}, in the panel's vocabulary. */
    static JSONObject haReading(JSONObject state)throws JSONException{
        String id=state.optString("entity_id"),value=state.optString("state");JSONObject a=state.optJSONObject("attributes");if(a==null)a=new JSONObject();
        JSONObject out=new JSONObject().put("online",!value.equals("unavailable")&&!value.equals("unknown")&&!value.isEmpty());if(!out.getBoolean("online"))return out;
        if(id.startsWith("light.")||id.startsWith("switch.")){JSONObject light=new JSONObject().put("on",value.equals("on"));Double b=number(a,"brightness");if(value.equals("on")&&b!=null)light.put("brightness",CloudMapper.clamp(b/255,.05,1));
            String mode=a.optString("color_mode");if(mode.equals("color_temp")){Double k=number(a,"color_temp_kelvin");if(k!=null){light.put("temp",CloudMapper.clamp(k,2200,6500));light.put("color",JSONObject.NULL);}}
            else if(Arrays.asList("hs","xy","rgb","rgbw","rgbww").contains(mode)){JSONArray c=a.optJSONArray("rgb_color");if(c!=null&&c.length()==3&&c.opt(0) instanceof Number&&c.opt(1) instanceof Number&&c.opt(2) instanceof Number)light.put("color",hex(c.getDouble(0)/255,c.getDouble(1)/255,c.getDouble(2)/255));}
            out.put("light",light);}
        else if(id.startsWith("climate.")){JSONObject modes=modeTable(strings(a.optJSONArray("hvac_modes"))),fans=fanTable(strings(a.optJSONArray("fan_modes")));JSONObject ac=new JSONObject().put("on",!value.equals("off"));
            Iterator<String> k=modes.keys();while(k.hasNext()){String m=k.next();if(modes.getString(m).equals(value))ac.put("mode",m);}
            Double t=number(a,"temperature"),lo=number(a,"target_temp_low"),hi=number(a,"target_temp_high"),room=number(a,"current_temperature");
            if(t!=null)ac.put("target",t);else if(lo!=null&&hi!=null)ac.put("target",(double)Math.round((lo+hi)/2));if(room!=null)ac.put("roomTemp",room);
            String fan=a.optString("fan_mode");String panel=fan.isEmpty()?null:panelFan(fan);if(panel!=null&&fans.has(panel))ac.put("fan",panel);
            out.put("ac",ac).put("modes",modes).put("fans",fans);}
        else if(id.startsWith("sensor.")){try{double t=Double.parseDouble(value);if(Double.isFinite(t))out.put("temperature","°F".equals(a.optString("unit_of_measurement"))?(t-32)*5/9:t);}catch(NumberFormatException ignored){}}
        return out;
    }
    static JSONObject call(String domain,String service,String id,JSONObject data)throws JSONException{JSONObject d=data==null?new JSONObject():CloudMapper.copy(data);d.put("entity_id",id);return new JSONObject().put("domain",domain).put("service",service).put("data",d);}
    /** The service calls that apply a panel patch. `fans`/`modes` are the entity's own names, from the last read. */
    static JSONArray haCalls(String id,JSONObject patch,JSONObject state,JSONObject fans,JSONObject modes)throws JSONException{
        String domain=id.substring(0,Math.max(0,id.indexOf('.')));JSONArray out=new JSONArray();Object on=patch.opt("on");
        if(domain.equals("switch")){if(on instanceof Boolean)out.put(call(domain,(Boolean)on?"turn_on":"turn_off",id,null));return out;}
        if(domain.equals("light")){if(Boolean.FALSE.equals(on))return out.put(call(domain,"turn_off",id,null));JSONObject data=new JSONObject();
            if(patch.has("brightness"))data.put("brightness_pct",(int)Math.round(patch.getDouble("brightness")*100));if(patch.has("temp"))data.put("color_temp_kelvin",(int)patch.getDouble("temp"));
            double[] c=patch.isNull("color")?null:rgb(patch.optString("color",null));if(c!=null)data.put("rgb_color",new JSONArray().put((int)(c[0]*255)).put((int)(c[1]*255)).put((int)(c[2]*255)));
            if(data.length()>0||Boolean.TRUE.equals(on))out.put(call(domain,"turn_on",id,data));return out;}
        if(!domain.equals("climate"))return out;
        if(Boolean.FALSE.equals(on))return out.put(call(domain,"set_hvac_mode",id,new JSONObject().put("hvac_mode","off")));
        // Picking a mode is what switches a climate entity on.
        if(Boolean.TRUE.equals(on)||patch.has("mode")){String mode=patch.optString("mode",state.optString("mode","cool"));String name=modes.optString(mode,null);if(name==null&&modes.keys().hasNext())name=modes.getString(modes.keys().next());if(name!=null)out.put(call(domain,"set_hvac_mode",id,new JSONObject().put("hvac_mode",name)));}
        if(patch.has("target"))out.put(call(domain,"set_temperature",id,new JSONObject().put("temperature",patch.getDouble("target"))));
        if(patch.has("fan")&&fans.has(patch.getString("fan")))out.put(call(domain,"set_fan_mode",id,new JSONObject().put("fan_mode",fans.getString(patch.getString("fan")))));
        return out;
    }
    /** "homeassistant.local:8123" → "http://homeassistant.local:8123". */
    static String haURL(String raw)throws IOException{
        String s=raw==null?"":raw.trim();if(s.isEmpty())throw new IOException("Enter the address of your Home Assistant, for example http://192.168.1.20:8123");if(!s.contains("://"))s="http://"+s;while(s.endsWith("/"))s=s.substring(0,s.length()-1);
        URI u;try{u=new URI(s);}catch(URISyntaxException e){throw new IOException("This is not a web address.");}
        if(u.getHost()==null||!(u.getScheme().equals("http")||u.getScheme().equals("https"))||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||(u.getRawPath()!=null&&!u.getRawPath().isEmpty()))throw new IOException("Enter only the server address, for example http://192.168.1.20:8123");
        if(u.getPort()==-1&&u.getScheme().equals("http")&&(u.getHost().endsWith(".local")||u.getHost().equals("homeassistant")))s+=":8123";return s;
    }

    // --- Philips Hue (CLIP v2) ---
    static boolean hueId(String id){return id!=null&&id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    /** "https://192.168.1.2/" → "192.168.1.2"; a port is kept ("192.168.1.2:8443"). */
    static String hueHost(String raw)throws IOException{String h=raw==null?"":raw.trim().replaceFirst("^https?://","");while(h.endsWith("/"))h=h.substring(0,h.length()-1);
        if(!h.matches("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?|\\[[0-9A-Fa-f:]{2,45}\\](:[0-9]{1,5})?"))throw new IOException("Enter the bridge address shown in the Hue app (Settings → My Hue system).");return h;}
    static String bare(String host){if(host.startsWith("[")&&host.indexOf(']')>0)return host.substring(1,host.indexOf(']'));int c=host.indexOf(':');return c>0&&host.indexOf(':',c+1)<0?host.substring(0,c):host;}
    static JSONObject hueLight(JSONObject light)throws JSONException{
        JSONObject out=new JSONObject();JSONObject on=light.optJSONObject("on");if(on!=null&&on.opt("on") instanceof Boolean)out.put("on",on.getBoolean("on"));
        Double b=number(light.optJSONObject("dimming"),"brightness");if(b!=null)out.put("brightness",CloudMapper.clamp(b/100,.05,1));
        JSONObject ct=light.optJSONObject("color_temperature");Double mirek=number(ct,"mirek");JSONObject xy=light.optJSONObject("color")==null?null:light.getJSONObject("color").optJSONObject("xy");
        if(ct!=null&&ct.optBoolean("mirek_valid")&&mirek!=null){out.put("temp",CloudMapper.clamp(kelvin(mirek),2200,6500));out.put("color",JSONObject.NULL);}
        else if(number(xy,"x")!=null&&number(xy,"y")!=null)out.put("color",hexXY(number(xy,"x"),number(xy,"y")));return out;
    }
    static Double hueTemperature(JSONObject sensor){JSONObject t=sensor.optJSONObject("temperature");if(t==null)return null;Double r=number(t.optJSONObject("temperature_report"),"temperature");return r!=null?r:number(t,"temperature");}
    static JSONObject hueBody(JSONObject patch)throws JSONException{JSONObject body=new JSONObject();
        if(patch.opt("on") instanceof Boolean)body.put("on",new JSONObject().put("on",patch.getBoolean("on")));
        if(patch.has("brightness"))body.put("dimming",new JSONObject().put("brightness",Math.round(patch.getDouble("brightness")*1000)/10.));
        if(patch.has("temp"))body.put("color_temperature",new JSONObject().put("mirek",(int)Math.round(mired(patch.getDouble("temp")))));
        double[] p=patch.isNull("color")?null:xy(patch.optString("color",null));if(p!=null)body.put("color",new JSONObject().put("xy",new JSONObject().put("x",p[0]).put("y",p[1])));return body;}

    // --- Addresses ---
    /** Local-network addresses only: a token or bridge key is never sent past the home router over plain HTTP. */
    /** Tests run the mock hubs on loopback; on the TV, loopback is the TV itself and stays refused. */
    static boolean allowLoopback=false;
    static boolean privateAddress(InetAddress a){if(allowLoopback&&a.isLoopbackAddress())return true;if(a.isSiteLocalAddress()||a.isLinkLocalAddress())return true;byte[] b=a.getAddress();return (b.length==16&&(b[0]&0xfe)==0xfc)||(b.length==4&&(b[0]&255)==100&&(b[1]&0xc0)==64);}
    static void requirePrivate(String host)throws IOException{InetAddress[] all;try{all=InetAddress.getAllByName(bare(host));}catch(UnknownHostException e){throw new IOException("Cannot find "+bare(host)+" on this network. Use its IP address.");}
        for(InetAddress a:all)if(!privateAddress(a))throw new IOException(bare(host)+" is not on your home network.");}
}
