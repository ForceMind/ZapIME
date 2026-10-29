// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.content.Context;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** Read-only snapshot guard. Not a binding operation or upload authorization. */
final class SyncDatabase {
    static void check(Context context,UUID account)throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_profile_requires_worker");
        String root=new File(context.getFilesDir(),"zapime").getAbsolutePath();
        validate(NativeCore.syncProfile(root),account);
    }
    static void validate(String response,UUID account)throws IOException {
        try{
            if(account==null||account.equals(new UUID(0,0))||response==null||response.length()>512)throw new IllegalArgumentException();
            JSONObject state=new JSONObject(response);
            if(state.length()!=2||!(state.get("initialized") instanceof Boolean)||!state.has("account_id"))throw new IllegalArgumentException();
            Object raw=state.get("account_id");
            if(raw==JSONObject.NULL)return;
            if(!state.getBoolean("initialized")||!(raw instanceof String))throw new IllegalArgumentException();
            UUID bound=UUID.fromString((String)raw);
            if(bound.equals(new UUID(0,0))||!bound.toString().equals(raw))throw new IllegalArgumentException();
            if(!bound.equals(account))throw new IOException("sync_connection_profile");
        }catch(IOException mismatch){throw mismatch;}
        catch(Exception invalid){throw new IOException("sync_profile_read_failed");}
    }
    private SyncDatabase(){}
}
