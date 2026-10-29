// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.content.Context;
import android.os.Looper;
import java.io.IOException;
import java.util.*;

/** Only the account UUID index is plain; server and bearer credential live in their own vault. */
final class SyncConnectionStore {
    private static void worker()throws IOException{if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_connection_requires_worker");}
    private static UUID active(Context context)throws IOException {
        String raw=context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).getString("active_account",null);
        if(raw==null)return null;
        try{UUID id=UUID.fromString(raw);if(!id.toString().equals(raw)||id.equals(new UUID(0,0)))throw new IllegalArgumentException();return id;}
        catch(RuntimeException invalid){throw new IOException("sync_connection_index");}
    }
    static SyncConnection load(Context context)throws IOException {
        worker();synchronized(SyncConnectionStore.class){
            UUID account=active(context);if(account==null)return null;
            byte[] bytes=SyncSecrets.loadConnection(context,account);if(bytes==null)throw new IOException("sync_connection_missing");
            try{SyncConnection result=SyncConnection.stored(bytes);if(!result.account.equals(account)){result.close();throw new IOException("sync_connection_identity");}return result;}
            finally{Arrays.fill(bytes,(byte)0);}
        }
    }
    static void save(Context context,SyncConnection connection)throws IOException {
        worker();synchronized(SyncConnectionStore.class){
            try(SyncConnection previous=load(context)){
                if(previous!=null&&(!previous.account.equals(connection.account)||!previous.server.equals(connection.server)))throw new IOException("sync_connection_switch");
            }
            byte[] bytes=connection.encode();
            try{SyncSecrets.saveConnection(context,connection.account,bytes);}
            finally{Arrays.fill(bytes,(byte)0);}
            if(!context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).edit().putString("active_account",connection.account.toString()).commit())throw new IOException("sync_connection_index");
        }
    }
    static void forget(Context context)throws IOException {
        worker();synchronized(SyncConnectionStore.class){
            UUID account=active(context);if(account!=null)SyncSecrets.forgetConnection(context,account);
            if(!context.getSharedPreferences("sync_connection",Context.MODE_PRIVATE).edit().remove("active_account").commit())throw new IOException("sync_connection_index");
        }
    }
    private SyncConnectionStore(){}
}
