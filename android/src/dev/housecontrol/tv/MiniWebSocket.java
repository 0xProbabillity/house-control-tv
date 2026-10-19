package dev.housecontrol.tv;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.Base64;
import javax.net.ssl.*;

/** The part of RFC 6455 a Home Assistant subscription needs: one text conversation with client-masked frames. */
final class MiniWebSocket implements Closeable {
    static final int MAX=8*1024*1024;
    final Socket socket;final InputStream in;final OutputStream out;final SecureRandom random=new SecureRandom();
    private MiniWebSocket(Socket s,InputStream in,OutputStream out){socket=s;this.in=in;this.out=out;}
    static String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>8192)throw new IOException("Header too large");}return c==-1&&b.size()==0?null:new String(b.toByteArray(),SharingTransport.UTF8);}
    /** `url` is http(s)://host:port/path; https gets the system's certificate and hostname checks. */
    static MiniWebSocket open(String url)throws Exception{
        URI u=new URI(url);boolean tls=u.getScheme().equals("https");String host=u.getHost();int port=u.getPort()==-1?(tls?443:80):u.getPort();
        Socket s=new Socket(Proxy.NO_PROXY);s.connect(new InetSocketAddress(host,port),5000);
        if(tls){SSLSocket ssl=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(s,host,port,true);ssl.startHandshake();
            if(!HttpsURLConnection.getDefaultHostnameVerifier().verify(host,ssl.getSession())){ssl.close();throw new IOException("The server certificate does not match "+host+".");}s=ssl;}
        try{s.setSoTimeout(10000);byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);String key=Base64.getEncoder().encodeToString(nonce);
            OutputStream out=s.getOutputStream();String path=u.getRawPath()==null||u.getRawPath().isEmpty()?"/":u.getRawPath();
            out.write(("GET "+path+" HTTP/1.1\r\nHost: "+(host.contains(":")?"["+host+"]":host)+(u.getPort()==-1?"":":"+port)+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(SharingTransport.UTF8));out.flush();
            InputStream in=new BufferedInputStream(s.getInputStream());String status=line(in);if(status==null||!status.startsWith("HTTP/1.1 101"))throw new IOException("The server refused the live-update connection.");
            String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(SharingTransport.UTF8)));boolean ok=false;String h;
            while((h=line(in))!=null&&!h.isEmpty()){int c=h.indexOf(':');if(c>0&&h.substring(0,c).trim().equalsIgnoreCase("sec-websocket-accept")&&h.substring(c+1).trim().equals(accept))ok=true;}
            if(!ok)throw new IOException("Invalid live-update handshake.");return new MiniWebSocket(s,in,out);
        }catch(Exception e){s.close();throw e;}
    }
    private void frame(int opcode,byte[] p)throws IOException{
        ByteArrayOutputStream f=new ByteArrayOutputStream();f.write(0x80|opcode);
        if(p.length<126)f.write(0x80|p.length);else if(p.length<65536){f.write(0x80|126);f.write(p.length>>8);f.write(p.length&255);}else{f.write(0x80|127);for(int i=7;i>=0;i--)f.write((int)(((long)p.length>>(8*i))&255));}
        byte[] mask=new byte[4];random.nextBytes(mask);f.write(mask);for(int i=0;i<p.length;i++)f.write(p[i]^mask[i&3]);synchronized(out){out.write(f.toByteArray());out.flush();}
    }
    void send(String text)throws IOException{frame(1,text.getBytes(SharingTransport.UTF8));}
    private int next()throws IOException{int b=in.read();if(b<0)throw new EOFException();return b;}
    private byte[] exactly(int n)throws IOException{byte[] b=new byte[n];int o=0,r;while(o<n&&(r=in.read(b,o,n-o))!=-1)o+=r;if(o!=n)throw new EOFException();return b;}
    /** The next text message, or null when nothing started arriving within `idleMillis`. */
    String read(int idleMillis)throws IOException{
        ByteArrayOutputStream message=new ByteArrayOutputStream();
        while(true){int b0;socket.setSoTimeout(idleMillis);
            try{b0=in.read();}catch(SocketTimeoutException e){if(message.size()==0)return null;throw e;}
            if(b0<0)throw new EOFException();socket.setSoTimeout(15000);
            int b1=next();long len=b1&127;if(len==126)len=(next()<<8)|next();else if(len==127){len=0;for(int i=0;i<8;i++)len=(len<<8)|next();}
            if(len<0||len+message.size()>MAX)throw new IOException("Live update too large.");
            byte[] mask=(b1&0x80)!=0?exactly(4):null,payload=exactly((int)len);if(mask!=null)for(int i=0;i<payload.length;i++)payload[i]^=mask[i&3];
            int op=b0&15;if(op==8)throw new EOFException();if(op==9){frame(10,payload);continue;}if(op==10)continue;
            message.write(payload);if((b0&0x80)!=0)return new String(message.toByteArray(),SharingTransport.UTF8);
        }
    }
    @Override public void close(){try{socket.close();}catch(IOException ignored){}}
}
