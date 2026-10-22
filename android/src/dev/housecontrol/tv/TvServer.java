package dev.housecontrol.tv;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated phone companion served by the TV itself while its app is open. */
final class TvServer {
    final Context context;final HomeEngine engine;final String token;final ServerSocket server;
    final ExecutorService clients=Executors.newFixedThreadPool(4);
    volatile boolean closed=false;
    TvServer(Context context,HomeEngine engine,String token)throws Exception{
        this.context=context;this.engine=engine;this.token=token;server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(8788));
        Thread thread=new Thread(()->{while(!closed)try{Socket socket=server.accept();clients.execute(()->serve(socket));}catch(Exception ignored){}},"tv-http");thread.setDaemon(true);thread.start();
    }
    String endpoint(){
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){NetworkInterface n=interfaces.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress> a=n.getInetAddresses();while(a.hasMoreElements()){InetAddress ip=a.nextElement();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress())return "http://"+ip.getHostAddress()+":8788";}}}catch(Exception ignored){}return "";
    }
    static String line(InputStream in,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n')return new String(out.toByteArray(),SharingTransport.UTF8).replace("\r","");out.write(c);if(out.size()>max)throw new IOException("Header too large");}return out.size()==0?null:new String(out.toByteArray(),SharingTransport.UTF8);}
    void serve(Socket socket){try(Socket s=socket){s.setSoTimeout(7000);InputStream in=new BufferedInputStream(s.getInputStream());String request=line(in,4096);if(request==null)return;String[] parts=request.split(" ");if(parts.length!=3)return;String method=parts[0],path=parts[1].split("\\?",2)[0];Map<String,String> headers=new HashMap<>();String h;int total=0;
        while((h=line(in,4096))!=null&&!h.isEmpty()){total+=h.length();if(total>16384)throw new IOException("Header too large");int colon=h.indexOf(':');if(colon>0)headers.put(h.substring(0,colon).trim().toLowerCase(Locale.ROOT),h.substring(colon+1).trim());}
        if(!method.equals("GET")&&!method.equals("POST")){reply(s,405,"text/plain","Method not allowed".getBytes(SharingTransport.UTF8));return;}
        if(path.startsWith("/api/")){
            String auth=headers.getOrDefault("authorization","");if(!MessageDigest.isEqual(auth.getBytes(SharingTransport.UTF8),("Bearer "+token).getBytes(SharingTransport.UTF8))){json(s,401,new JSONObject().put("error","Open the private QR link from your TV."));return;}
            Set<String> gets=new HashSet<>(Arrays.asList("/api/home","/api/auth/status","/api/connections"));if((method.equals("GET")&&!gets.contains(path))||(method.equals("POST")&&gets.contains(path))){json(s,405,new JSONObject().put("error","Invalid request method."));return;}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length<0||length>512*1024){json(s,413,new JSONObject().put("error","Import is too large."));return;}
            byte[] body=new byte[length];int offset=0,n;while(offset<length&&(n=in.read(body,offset,length-offset))!=-1)offset+=n;if(offset!=length)throw new IOException("Incomplete request");
            try{JSONObject result=engine.handle(path,length==0?new JSONObject():new JSONObject(new String(body,SharingTransport.UTF8)));if(path.equals("/api/home"))result.put("endpoint",endpoint());json(s,200,result);}catch(Exception e){json(s,400,new JSONObject().put("error",HomeEngine.safe(e)));}
        }else{
            if(!method.equals("GET")){reply(s,405,"text/plain",new byte[0]);return;}
            String asset=path.equals("/")||path.equals("/remote")?"index.html":path.substring(1);if(asset.contains("..")||asset.contains("%")||asset.contains("\\")){reply(s,404,"text/plain",new byte[0]);return;}
            try(InputStream file=context.getAssets().open("web/"+asset)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=file.read(b))!=-1)bytes.write(b,0,n);String mime=asset.endsWith(".js")?"application/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".json")?"application/json":asset.endsWith(".jpg")?"image/jpeg":asset.endsWith(".png")?"image/png":"text/html";reply(s,200,mime,bytes.toByteArray());}catch(FileNotFoundException e){reply(s,404,"text/plain",new byte[0]);}
        }
    }catch(Exception ignored){}}
    static void json(Socket s,int status,JSONObject object)throws Exception{reply(s,status,"application/json",object.toString().getBytes(SharingTransport.UTF8));}
    static void reply(Socket s,int status,String type,byte[] body)throws Exception{OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+status+" Response\r\nContent-Type: "+type+"; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n").getBytes(SharingTransport.UTF8));out.write(body);out.flush();}
    void close(){closed=true;try{server.close();}catch(Exception ignored){}clients.shutdownNow();}
}
