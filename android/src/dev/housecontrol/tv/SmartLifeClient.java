package dev.housecontrol.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Independent Smart Life device-sharing login and HTTPS transport. No Mac tokens are imported. */
final class SmartLifeClient {
    static final String CLIENT="HA_3y9q4ak7g4ephrvke", LOGIN="https://apigw.iotbing.com";
    final SecureVault vault;
    volatile JSONObject session;
    volatile int commandAttempts=0;
    final Map<String,Set<String>> codes=new HashMap<>();
    final Map<String,JSONObject> ranges=new HashMap<>();
    static class Failure extends IOException { final String code;Failure(String message,String code){super(message);this.code=code;} }
    SmartLifeClient(SecureVault vault)throws Exception{this.vault=vault;session=vault.read();}
    boolean connected(){return session!=null;}
    static String encoded(String text)throws Exception{return URLEncoder.encode(text,"UTF-8");}
    static boolean trustedEndpoint(String endpoint){
        try{URL u=new URL(endpoint);String h=u.getHost().toLowerCase(Locale.ROOT);return u.getProtocol().equals("https")&&(h.endsWith(".tuya.com")||h.endsWith(".iotbing.com")||h.endsWith(".tuyaeu.com")||h.endsWith(".tuyaus.com")||h.endsWith(".tuyacn.com")||h.endsWith(".tuyain.com"))&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443);}catch(Exception e){return false;}
    }
    synchronized JSONObject begin(String userCode)throws Exception{
        if(!userCode.matches("[A-Za-z0-9_-]{3,64}"))throw new IOException("Enter the User Code from Smart Life → Me → Settings → Account and Security.");
        JSONObject j=http("POST",LOGIN+"/v1.0/m/life/home-assistant/qrcode/tokens?clientid="+CLIENT+"&usercode="+encoded(userCode)+"&schema=haauthorize",null,null);
        check(j);String token=j.getJSONObject("result").getString("qrcode");
        return new JSONObject().put("loginToken",token).put("userCode",userCode).put("qrContent","tuyaSmart--qrLogin?token="+token);
    }
    synchronized boolean finish(String token,String userCode)throws Exception{
        JSONObject j=http("GET",LOGIN+"/v1.0/m/life/home-assistant/qrcode/tokens/"+encoded(token)+"?clientid="+CLIENT+"&usercode="+encoded(userCode),null,null);
        if(!j.optBoolean("success"))return false;JSONObject r=j.optJSONObject("result");if(r==null||!r.has("access_token"))return false;
        if(!trustedEndpoint(r.optString("endpoint")))throw new IOException("Smart Life returned an unrecognized HTTPS endpoint.");
        JSONObject next=new JSONObject().put("userCode",userCode).put("accessToken",r.getString("access_token")).put("refreshToken",r.getString("refresh_token")).put("endpoint",r.getString("endpoint")).put("uid",r.optString("uid")).put("expiresAt",j.optLong("t",System.currentTimeMillis())+r.optLong("expire_time",7200)*1000);
        vault.write(next);session=next;return true;
    }
    synchronized void signOut(){vault.clear();session=null;codes.clear();ranges.clear();}
    private void refresh()throws Exception{
        if(session==null||session.optLong("expiresAt")-60000>System.currentTimeMillis())return;
        Object result=request("GET","/v1.0/m/token/"+encoded(session.getString("refreshToken")),null,null,false);
        if(!(result instanceof JSONObject))throw new IOException("Smart Life login expired. Connect again.");JSONObject r=(JSONObject)result;
        JSONObject next=new JSONObject(session.toString()).put("accessToken",r.getString("accessToken")).put("refreshToken",r.getString("refreshToken")).put("expiresAt",System.currentTimeMillis()+r.optLong("expireTime",7200)*1000);
        vault.write(next);session=next;
    }
    synchronized Object request(String method,String path,JSONObject query,JSONObject body)throws Exception{return request(method,path,query,body,true);}
    private Object request(String method,String path,JSONObject query,JSONObject body,boolean refresh)throws Exception{
        if(refresh)refresh();if(session==null)throw new IOException("Connect Smart Life on this TV first.");
        String endpoint=session.getString("endpoint");if(!trustedEndpoint(endpoint))throw new IOException("Invalid Smart Life endpoint.");
        String id=UUID.randomUUID().toString(),hash=SharingTransport.md5(id+session.getString("refreshToken")),secret=SharingTransport.secret(id,hash);
        String qe=query==null||query.length()==0?"":SharingTransport.encrypt(query.toString(),secret);
        String be=body==null||body.length()==0?"":SharingTransport.encrypt(body.toString(),secret);
        LinkedHashMap<String,String> headers=new LinkedHashMap<>();headers.put("X-appKey",CLIENT);headers.put("X-requestId",id);headers.put("X-sid","");headers.put("X-time",String.valueOf(System.currentTimeMillis()));headers.put("X-token",session.getString("accessToken"));
        String signature=SharingTransport.sign(hash,headers,qe,be);headers.put("X-sign",signature);
        JSONObject j=http(method,endpoint+path+(qe.isEmpty()?"":"?encdata="+encoded(qe)),headers,be.isEmpty()?null:new JSONObject().put("encdata",be));check(j);
        Object result=j.opt("result");if(result instanceof String&&!((String)result).isEmpty())return new JSONTokener(SharingTransport.decrypt((String)result,secret)).nextValue();return result;
    }
    synchronized Map<String,JSONArray> status(Set<String> ids)throws Exception{
        List<String> list=new ArrayList<>(ids);Map<String,JSONArray> out=new HashMap<>();
        for(int start=0;start<list.size();start+=20){String joined=String.join(",",list.subList(start,Math.min(list.size(),start+20)));
            Object result=request("GET","/v1.0/m/life/ha/devices/detail",new JSONObject().put("devIds",joined),null);if(!(result instanceof JSONArray))throw new IOException("Invalid device status response.");
            JSONArray devices=(JSONArray)result;for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);JSONArray s=d.optJSONArray("status");if(s==null)continue;String id=d.getString("id");out.put(id,s);Set<String> known=codes.computeIfAbsent(id,k->new HashSet<>());for(int n=0;n<s.length();n++)known.add(s.getJSONObject(n).optString("code"));}
        }
        return out;
    }
    synchronized void prepare(String id)throws Exception{
        if(ranges.containsKey(id))return;
        Object result=request("GET","/v1.1/m/life/"+encoded(id)+"/specifications",null,null);JSONObject spec=result instanceof JSONObject?(JSONObject)result:new JSONObject();
        Set<String> known=codes.computeIfAbsent(id,k->new HashSet<>());JSONObject range=new JSONObject();
        for(String section:new String[]{"status","functions"}){JSONArray a=spec.optJSONArray(section);if(a==null)continue;for(int i=0;i<a.length();i++){JSONObject item=a.getJSONObject(i);String code=item.getString("code");known.add(code);try{JSONObject values=new JSONObject(item.optString("values","{}"));if(values.has("min")&&values.has("max"))range.put(code,values);}catch(JSONException ignored){}}}
        ranges.put(id,range);
    }
    synchronized void send(String id,JSONArray commands)throws Exception{if(commands.length()>0){commandAttempts++;request("POST","/v1.1/m/thing/"+encoded(id)+"/commands",null,new JSONObject().put("commands",commands));}}
    synchronized void triggerScene(JSONObject command)throws Exception{
        commandAttempts++;
        Object result=request("POST","/v1.0/m/scene/ha/trigger",null,command);
        if(!Boolean.TRUE.equals(result))throw new IOException("Smart Life did not run the linked IR command.");
    }
    static void check(JSONObject j)throws Exception{
        if(j.optBoolean("success"))return;String code=String.valueOf(j.opt("code"));
        throw new Failure(code.equals("2001")?"Device is offline in Smart Life.":j.optString("msg","Smart Life request failed")+" ("+code+")",code);
    }
    static JSONObject http(String method,String url,Map<String,String> headers,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
        try{if(headers!=null)for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(SharingTransport.UTF8));}}
            int status=c.getResponseCode();InputStream stream=status<400?c.getInputStream():c.getErrorStream();if(stream==null)throw new IOException("Smart Life is unavailable (HTTP "+status+").");
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=stream){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>4*1024*1024)throw new IOException("Response too large.");}}
            return new JSONObject(new String(out.toByteArray(),SharingTransport.UTF8));
        }finally{c.disconnect();}
    }
}
