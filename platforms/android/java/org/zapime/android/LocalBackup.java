// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.content.Context;
import android.os.Looper;
import java.io.*;
import org.json.JSONObject;

/** All KDF, snapshot and restore work stays off input/sync/UI workers. */
final class LocalBackup {
    private static void worker()throws IOException{if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("backup_requires_worker");}
    private static String root(Context context){return new File(context.getFilesDir(),"zapime").getAbsolutePath();}
    static byte[] create(Context context,byte[] code)throws IOException {
        worker();if(code==null||code.length!=43)throw new IOException("backup_failed");
        byte[] encrypted=NativeCore.createLocalBackup(root(context),code);
        if(encrypted==null||encrypted.length==0||encrypted.length>BackupFiles.MAX)throw new IOException("backup_snapshot_unavailable");return encrypted;
    }
    static int restore(Context context,byte[] code,byte[] encrypted)throws IOException {
        worker();if(code==null||code.length!=43||encrypted==null||encrypted.length==0||encrypted.length>BackupFiles.MAX)throw new IOException("invalid_or_unauthenticated_backup");
        try{JSONObject result=new JSONObject(NativeCore.restoreLocalBackup(root(context),code,encrypted));
            if(result.has("error"))throw new IOException(result.getString("error"));
            Object count=result.get("restored_records");if(result.length()!=1||!(count instanceof Integer)||((Integer)count)<0)throw new IOException("backup_failed");return (Integer)count;
        }catch(IOException fixed){throw fixed;}catch(Exception invalid){throw new IOException("backup_failed");}
    }
    private LocalBackup(){}
}
