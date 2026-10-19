package dev.housecontrol.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.*;
import android.net.Uri;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private WebView web;
    private HomeEngine engine;
    private TvServer localServer;
    private String startupError="";
    private SharedPreferences prefs;
    private boolean homeBound;
    // Hold the engine/server for this Activity's lifetime, including QR sign-in.
    // A started-only service can be stopped as idle while the WebView retains it.
    private final ServiceConnection homeConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) { }
        @Override public void onServiceDisconnected(ComponentName name) { }
    };
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private static final String ORIGIN = "https://housecontrol.local/";
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("connection", MODE_PRIVATE);
        try {
            homeBound=bindService(new Intent(this,HomeService.class).setAction(HomeService.ACTIVITY_BIND),homeConnection,Context.BIND_AUTO_CREATE);
            if(!homeBound)throw new IOException("House Control service could not connect.");
            HomeService.initialize(this);
            engine=HomeService.sharedEngine;localServer=HomeService.sharedServer;engine.setActive(true);
        } catch(Exception error){startupError=HomeEngine.safe(error);}
        configure(getIntent());
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        web = new WebView(this); web.setBackgroundColor(0xff0a0e1a);
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportZoom(false); s.setBuiltInZoomControls(false);
        web.addJavascriptInterface(new NativeBridge(), "HouseTV");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) { Log.d("HouseTV", m.message() + " @" + m.lineNumber()); return true; }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !r.getUrl().toString().startsWith(ORIGIN); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri uri = r.getUrl();
                if (!"housecontrol.local".equals(uri.getHost()) || !"https".equals(uri.getScheme())) return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                String path = uri.getPath(); if (path == null || path.equals("/")) path = "/index.html";
                if (path.contains("..")) return null;
                String mime = path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css" : path.endsWith(".json") ? "application/json" : path.endsWith(".png") ? "image/png" : "text/html";
                try { return new WebResourceResponse(mime, "utf-8", getAssets().open("web" + path)); }
                catch (IOException e) { return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0])); }
            }
        });
        setContentView(web); web.loadUrl(ORIGIN + "index.html"); web.requestFocus();
    }
    private void configure(Intent i) {
        if((i.hasExtra("homeImport")||i.hasExtra("remoteKey"))&&engine!=null&&engine.plan!=null){
            String expected=prefs.getString("remoteKey", ""),provided=i.getStringExtra("provisionKey");
            if(provided==null||expected.isEmpty()||!java.security.MessageDigest.isEqual(expected.getBytes(SharingTransport.UTF8),provided.getBytes(SharingTransport.UTF8))){startupError="Use your paired Mac or the private phone link to update this TV.";return;}
        }
        i.removeExtra("provisionKey");
        String remoteKey=i.getStringExtra("remoteKey");
        if(remoteKey!=null&&remoteKey.matches("[A-Za-z0-9_-]{32,128}")&&!remoteKey.equals(prefs.getString("remoteKey",""))){
            prefs.edit().putString("remoteKey",remoteKey).apply();
            try{if(engine!=null){HomeService.replaceServer(this,remoteKey);localServer=HomeService.sharedServer;}}catch(Exception e){startupError=HomeEngine.safe(e);}
            i.removeExtra("remoteKey");
        }
        i.removeExtra("remoteKey");
        String encoded=i.getStringExtra("homeImport");
        if(encoded!=null && engine!=null)try{
            byte[] data=java.util.Base64.getUrlDecoder().decode(encoded);
            if(data.length>512*1024)throw new IOException("Home import is too large.");
            engine.importHome(new JSONObject(new String(data,"UTF-8")));
            i.removeExtra("homeImport");
        }catch(Exception e){startupError=HomeEngine.safe(e);}
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); configure(i); if(web!=null)web.reload(); }
    @Override public void onBackPressed() { web.evaluateJavascript("window.tvBack && window.tvBack()", null); }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if(e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { web.evaluateJavascript("document.querySelector('.sync-button')?.click()", null); return true; }
        return super.dispatchKeyEvent(e);
    }
    @Override protected void onPause() { super.onPause(); if(engine!=null)engine.setActive(false); web.onPause(); web.evaluateJavascript("window.__houseTV?.world?.setRunning(false)", null); }
    @Override protected void onResume() { super.onResume(); if(engine!=null)engine.setActive(true); if(web!=null){web.onResume();web.evaluateJavascript("window.__houseTV?.world?.setRunning(true)", null);} }
    @Override protected void onDestroy() { network.shutdownNow(); if(web!=null){web.destroy();web=null;} if(homeBound){unbindService(homeConnection);homeBound=false;} super.onDestroy(); }
    final class NativeBridge {
        @JavascriptInterface public String getConfig() {
            try { return new JSONObject().put("mode","standalone").put("endpoint", localServer==null?"":localServer.endpoint()).put("token", prefs.getString("remoteKey", "")).put("version", engine==null?"":engine.version()).toString(); }
            catch(Exception e){ return "{}"; }
        }
        @JavascriptInterface public void exit() { runOnUiThread(() -> finish()); }
        @JavascriptInterface public void request(int id, String path, String body) {
            if (!java.util.Arrays.asList("/api/demo/action","/api/demo","/api/home","/api/action","/api/auth/status","/api/auth/start","/api/auth/check","/api/auth/logout","/api/import","/api/connections","/api/connections/discover","/api/homeassistant/connect","/api/homeassistant/disconnect","/api/hue/pair","/api/hue/disconnect").contains(path)) return;
            network.execute(() -> {
                JSONObject result;
                try {
                    if(engine==null)throw new IOException(startupError.isEmpty()?"TV engine could not start.":startupError);
                    result=engine.handle(path,body.isEmpty()?new JSONObject():new JSONObject(body));
                    if(path.equals("/api/home"))result.put("endpoint",localServer==null?"":localServer.endpoint());
                }catch(Exception error){try{result=new JSONObject().put("error",HomeEngine.safe(error));}catch(Exception ignored){result=new JSONObject();}}
                final String reply=result.toString();runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.__tvReply("+id+","+reply+")",null);});
            });
        }
    }
}
