// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.*;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local wrapping for opaque sync material. Not an account or recovery protocol.
 * Call only on a dedicated sync worker; callers must clear returned/input buffers.
 * No plaintext fallback, logging, root-key generation or automatic key replacement.
 */
final class SyncSecrets {
    private static final int MAX_MATERIAL=64*1024,HEADER=16,TAG=16;
    private static final byte[] MAGIC={'Z','K','V',1};
    private enum Purpose { MATERIAL, CONNECTION }
    private static void worker()throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_material_requires_worker");
    }
    private static String alias(UUID profile)throws IOException {return alias(profile,Purpose.MATERIAL);}
    private static String alias(UUID profile,Purpose purpose)throws IOException {
        if(profile==null||profile.equals(new UUID(0,0)))throw new IOException("sync_material_profile");
        return "org.zapime.android.sync."+(purpose==Purpose.MATERIAL?"material":"connection")+".v1."+profile;
    }
    private static File file(Context context,UUID profile)throws IOException {return file(context,profile,Purpose.MATERIAL);}
    private static File file(Context context,UUID profile,Purpose purpose)throws IOException {
        alias(profile,purpose);File parent=context.getNoBackupFilesDir();
        if(parent==null||!parent.isDirectory()||Files.isSymbolicLink(parent.toPath()))throw new IOException("sync_material_storage");
        File file=new File(parent,(purpose==Purpose.MATERIAL?"sync-material-":"sync-connection-")+profile+".bin");
        for(String suffix:new String[]{"",".bak",".new"}){
            File candidate=new File(file+suffix);
            if(Files.exists(candidate.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(candidate.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("sync_material_storage");
        }
        return file;
    }
    private static byte[] aad(UUID profile,Purpose purpose)throws IOException {
        return ("ZapIME/sync-"+(purpose==Purpose.MATERIAL?"material":"connection")+"/v1/"+alias(profile,purpose)).getBytes(StandardCharsets.UTF_8);
    }
    private static KeyStore keys()throws GeneralSecurityException,IOException {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;
    }
    private static SecretKey existing(KeyStore store,UUID profile,Purpose purpose)throws GeneralSecurityException,IOException {
        Key key=store.getKey(alias(profile,purpose),null);
        if(!(key instanceof SecretKey)||!"AES".equals(key.getAlgorithm()))throw new IOException("sync_key_unavailable");
        return (SecretKey)key;
    }
    private static boolean hasFiles(File file){return file.exists()||new File(file+".bak").exists()||new File(file+".new").exists();}
    static void save(Context context,UUID profile,byte[] material)throws IOException {
        worker();synchronized(SyncSecrets.class){saveLocked(context,profile,material,Purpose.MATERIAL);}
    }
    private static void saveLocked(Context context,UUID profile,byte[] material,Purpose purpose)throws IOException {
        if(material==null||material.length==0||material.length>MAX_MATERIAL)throw new IOException("sync_material_size");
        File file=file(context,profile,purpose);
        try{
            KeyStore store=keys();SecretKey key;
            if(store.containsAlias(alias(profile,purpose)))key=existing(store,profile,purpose);
            else {
                // A remaining cipher without its wrapping key needs explicit
                // recovery/reset, never silently generate a replacement key.
                if(hasFiles(file))throw new IOException("sync_key_unavailable");
                KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(alias(profile,purpose),KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
                key=generator.generateKey();
            }
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad(profile,purpose));
            byte[] iv=cipher.getIV();if(iv==null||iv.length!=12)throw new IOException("sync_material_cipher");
            byte[] encrypted=cipher.doFinal(material);
            AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;
            try{out=atomic.startWrite();out.write(MAGIC);out.write(iv);out.write(encrypted);out.getFD().sync();atomic.finishWrite(out);out=null;}
            finally{if(out!=null)atomic.failWrite(out);}
            // Framework finishWrite can log an I/O error instead of throwing.
            // Verify committed plaintext rather than claiming a lost write saved.
            byte[] committed=loadLocked(context,profile,purpose);
            try{if(committed==null||!MessageDigest.isEqual(material,committed))throw new IOException("sync_material_commit");}
            finally{if(committed!=null)Arrays.fill(committed,(byte)0);}
        }catch(GeneralSecurityException failure){throw new IOException("sync_material_crypto");}
    }
    /** null means no saved file; an existing unreadable cipher is always an error. */
    static byte[] load(Context context,UUID profile)throws IOException {
        worker();synchronized(SyncSecrets.class){return loadLocked(context,profile,Purpose.MATERIAL);}
    }
    private static byte[] loadLocked(Context context,UUID profile,Purpose purpose)throws IOException {
        File file=file(context,profile,purpose);byte[] wire;
        try(FileInputStream in=new AtomicFile(file).openRead()){
            if(in.getChannel().size()>MAX_MATERIAL+HEADER+TAG)throw new IOException("sync_material_size");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1){if(bytes.size()+count>MAX_MATERIAL+HEADER+TAG)throw new IOException("sync_material_size");bytes.write(buffer,0,count);}
            wire=bytes.toByteArray();
        }catch(FileNotFoundException absent){if(hasFiles(file))throw new IOException("sync_material_storage");return null;}
        if(wire.length<=HEADER+TAG||!Arrays.equals(Arrays.copyOfRange(wire,0,4),MAGIC))throw new IOException("sync_material_format");
        try{
            SecretKey key=existing(keys(),profile,purpose);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,wire,4,12));cipher.updateAAD(aad(profile,purpose));
            byte[] material=cipher.doFinal(wire,HEADER,wire.length-HEADER);
            if(material.length==0||material.length>MAX_MATERIAL){Arrays.fill(material,(byte)0);throw new IOException("sync_material_size");}
            return material;
        }catch(GeneralSecurityException failure){throw new IOException("sync_material_unreadable");}
    }
    // Dedicated namespace: removing an account connection must not delete E2EE material.
    static void saveConnection(Context context,UUID account,byte[] bytes)throws IOException {
        worker();synchronized(SyncSecrets.class){saveLocked(context,account,bytes,Purpose.CONNECTION);}
    }
    static byte[] loadConnection(Context context,UUID account)throws IOException {
        worker();synchronized(SyncSecrets.class){return loadLocked(context,account,Purpose.CONNECTION);}
    }
    static void forgetConnection(Context context,UUID account)throws IOException {
        worker();synchronized(SyncSecrets.class){forgetLocked(context,account,Purpose.CONNECTION);}
    }
    /** Only an explicit forget/reset action may call this. Material is separate from connection credentials. */
    static void forget(Context context,UUID profile)throws IOException {
        worker();synchronized(SyncSecrets.class){forgetLocked(context,profile,Purpose.MATERIAL);}
    }
    private static void forgetLocked(Context context,UUID profile,Purpose purpose)throws IOException {
        File file=file(context,profile,purpose);new AtomicFile(file).delete();
        // API28 AtomicFile predates .new; remove only our exact optional path.
        Files.deleteIfExists(new File(file+".new").toPath());
        if(hasFiles(file))throw new IOException("sync_material_storage");
        try{keys().deleteEntry(alias(profile,purpose));}catch(GeneralSecurityException failure){throw new IOException("sync_material_crypto");}
    }
}
