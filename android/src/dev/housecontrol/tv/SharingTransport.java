package dev.housecontrol.tv;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Tuya device-sharing envelope, ported from the upstream House Control client. */
public final class SharingTransport {
    static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
    public static String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString(); }
    public static String md5(String text) throws Exception { return hex(MessageDigest.getInstance("MD5").digest(text.getBytes(UTF8))); }
    public static String hmac(String key,String text) throws Exception { Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key.getBytes(UTF8),"HmacSHA256"));return hex(m.doFinal(text.getBytes(UTF8))); }
    public static String secret(String requestId,String hashKey) throws Exception { return hmac(requestId,hashKey).substring(0,16); }
    public static String sign(String hashKey,LinkedHashMap<String,String> headers,String query,String body) throws Exception {
        StringBuilder s=new StringBuilder();for(Map.Entry<String,String> h:headers.entrySet())if(!h.getValue().isEmpty()){if(s.length()>0)s.append("||");s.append(h.getKey()).append('=').append(h.getValue());}
        return hmac(hashKey,s.toString()+query+body);
    }
    public static String encrypt(String plain,String secret) throws Exception { byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);return encrypt(plain,secret,nonce); }
    public static String encrypt(String plain,String secret,byte[] nonce) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret.getBytes(UTF8),"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=c.doFinal(plain.getBytes(UTF8));byte[] combined=new byte[12+encrypted.length];System.arraycopy(nonce,0,combined,0,12);System.arraycopy(encrypted,0,combined,12,encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }
    public static String decrypt(String encoded,String secret) throws Exception {
        byte[] data=Base64.getDecoder().decode(encoded);if(data.length<28)throw new GeneralSecurityException("Invalid Smart Life envelope");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret.getBytes(UTF8),"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));
        return new String(c.doFinal(Arrays.copyOfRange(data,12,data.length)),UTF8);
    }
}
