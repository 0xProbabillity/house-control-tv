package dev.housecontrol.tv;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** A separate TV login or local hub credential, encrypted with a non-exportable Android Keystore key. */
final class SecureVault implements SecretStore {
    private final AtomicFile file;
    private final String alias="house-control-tv-smartlife-v1";
    SecureVault(Context context){this(context,"smartlife.enc");}
    SecureVault(Context context,String name){file=new AtomicFile(new File(context.getFilesDir(),name));}
    private javax.crypto.SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(alias)){
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (javax.crypto.SecretKey)store.getKey(alias,null);
    }
    public synchronized JSONObject read() throws Exception {
        if(!file.getBaseFile().exists())return null;
        byte[] data=file.readFully();if(data.length<28)throw new IOException("A saved TV connection needs to be renewed.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));
        return new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(data,12,data.length)),SharingTransport.UTF8));
    }
    public synchronized void write(JSONObject value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(value.toString().getBytes(SharingTransport.UTF8));FileOutputStream out=null;
        try{out=file.startWrite();out.write(cipher.getIV());out.write(encrypted);file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}
    }
    public synchronized void clear(){file.delete();}
}
