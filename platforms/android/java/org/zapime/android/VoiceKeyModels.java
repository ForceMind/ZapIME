// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.content.Context;
import android.os.Looper;
import java.io.*;
import java.util.function.BooleanSupplier;

/** User supplies an official package; only independently reviewed source pins authorize parsing. */
final class VoiceKeyModels {
    private static final Object LOCK=new Object();
    private static VoiceKeyModelStore store(Context context)throws IOException{return new VoiceKeyModelStore(new File(context.getFilesDir(),"voicekey"),VoiceKeyModelCatalog.require());}
    private static void worker()throws IOException{if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("voicekey_model_requires_worker");}
    // Bounded metadata hints only. A matching fixed catalog is mandatory even for hints.
    static boolean installed(Context context){if(!VoiceKeyModelCatalog.available())return false;try{return store(context).installed();}catch(IOException unavailable){return false;}}
    static boolean recoverable(Context context){if(!VoiceKeyModelCatalog.available())return false;try{return store(context).recoverable();}catch(IOException unavailable){return false;}}
    static boolean trustedCatalogAvailable(){return VoiceKeyModelCatalog.available();}
    static File verified(Context context,BooleanSupplier cancelled)throws Exception{worker();synchronized(LOCK){return store(context).verified(cancelled);}}
    static void importPackage(Context context,InputStream incoming,BooleanSupplier cancelled)throws Exception{worker();synchronized(LOCK){store(context).importPackage(incoming,cancelled);}}
    private VoiceKeyModels(){}
}
