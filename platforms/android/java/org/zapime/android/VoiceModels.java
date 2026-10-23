// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.json.*;

/** Only the model pinned in the APK is accepted. Imported manifests are never executed. */
final class VoiceModels {
    private static JSONObject config(Context context)throws Exception {
        try(InputStream in=context.getAssets().open("voice-model.json")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>16384)throw new IOException("model_catalog");out.write(buffer,0,n);}
            JSONObject config=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            if(config.getInt("schema_version")!=1||!config.getString("id").matches("[a-z0-9-]{1,80}"))throw new IOException("model_catalog");
            return config;
        }
    }
    private static File root(Context context){return new File(context.getFilesDir(),"voice");}
    private static File model(Context context,JSONObject config)throws Exception{return new File(root(context),config.getString("id"));}
    static boolean installed(Context context){
        try{return new File(model(context,config(context)),"msime-model.json").isFile();}catch(Exception e){return false;}
    }
    private static void check(BooleanSupplier cancelled)throws IOException {if(cancelled.getAsBoolean())throw new IOException("cancelled");}
    private static String hex(byte[] digest){StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private static boolean verifyFile(File file,JSONObject spec,BooleanSupplier cancelled)throws Exception {
        if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()!=spec.getLong("size"))return false;
        MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
        try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(buffer))!=-1){check(cancelled);sha.update(buffer,0,n);}}
        return hex(sha.digest()).equals(spec.getString("sha256"));
    }
    static synchronized File verified(Context context,BooleanSupplier cancelled)throws Exception {
        JSONObject config=config(context),files=config.getJSONObject("files");File model=model(context,config);
        if(Files.isSymbolicLink(model.toPath())||!model.isDirectory())throw new IOException("model_missing");
        Set<String> expected=new HashSet<>();Iterator<String> names=files.keys();
        while(names.hasNext()){String name=names.next();expected.add(name);if(!verifyFile(new File(model,name),files.getJSONObject(name),cancelled))throw new IOException("model_checksum");}
        expected.add("msime-model.json");String[] actual=model.list();
        if(actual==null||!expected.equals(new HashSet<>(Arrays.asList(actual))))throw new IOException("model_members");
        File manifest=new File(model,"msime-model.json");byte[] trusted=config.getJSONObject("manifest").toString().getBytes(StandardCharsets.UTF_8);
        JSONObject manifestSpec=new JSONObject().put("size",trusted.length).put("sha256",hex(MessageDigest.getInstance("SHA-256").digest(trusted)));
        if(!verifyFile(manifest,manifestSpec,cancelled))throw new IOException("model_manifest");check(cancelled);
        // Android's /data/user/0 alias is system-owned but its parent may be uid
        // 1000. Resolve it only after hashing our fixed app-private model; the
        // unchanged upstream Linux ancestor policy then sees real directories.
        return model.getCanonicalFile();
    }
    static synchronized void importZip(Context context,InputStream incoming,BooleanSupplier cancelled)throws Exception {
        JSONObject config=config(context),files=config.getJSONObject("files");File parent=root(context);
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("model_storage");
        if(Files.isSymbolicLink(parent.toPath()))throw new IOException("model_storage");
        File staging=new File(parent,"incoming-"+UUID.randomUUID());if(!staging.mkdir())throw new IOException("model_storage");
        File destination=model(context,config),previous=new File(parent,"previous-"+UUID.randomUUID());boolean displaced=false;
        try {
            Set<String> seen=new HashSet<>();byte[] buffer=new byte[65536];
            try(ZipInputStream zip=new ZipInputStream(incoming)){
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null){
                    check(cancelled);String name=entry.getName();
                    if(entry.isDirectory()||!files.has(name)||!seen.add(name)||!name.matches("[A-Za-z0-9._-]+"))throw new IOException("model_members");
                    JSONObject spec=files.getJSONObject(name);long limit=spec.getLong("size"),written=0;
                    if(entry.getSize()>limit)throw new IOException("model_size");
                    MessageDigest sha=MessageDigest.getInstance("SHA-256");
                    try(FileOutputStream out=new FileOutputStream(new File(staging,name))){
                        int n;while((n=zip.read(buffer))!=-1){check(cancelled);written+=n;if(written>limit)throw new IOException("model_size");out.write(buffer,0,n);sha.update(buffer,0,n);}
                        out.getFD().sync();
                    }
                    if(written!=limit||!hex(sha.digest()).equals(spec.getString("sha256")))throw new IOException("model_checksum");
                    zip.closeEntry();
                }
            }
            if(seen.size()!=files.length())throw new IOException("model_members");
            try(FileOutputStream out=new FileOutputStream(new File(staging,"msime-model.json"))){out.write(config.getJSONObject("manifest").toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            check(cancelled);
            // Immutable model id: if already valid there is nothing to replace or reload.
            try{verified(context,cancelled);return;}catch(IOException invalid){check(cancelled);}
            if(destination.exists()){Files.move(destination.toPath(),previous.toPath(),StandardCopyOption.ATOMIC_MOVE);displaced=true;}
            try{Files.move(staging.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);}
            catch(Exception failure){if(displaced)Files.move(previous.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE);throw failure;}
            remove(previous);
        } finally {remove(staging);}
    }
    private static void remove(File file)throws IOException {
        if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath())){File[] children=file.listFiles();if(children==null)throw new IOException("model_storage");for(File child:children)remove(child);}
        if(file.exists()&&!file.delete())throw new IOException("model_storage");
    }
}
