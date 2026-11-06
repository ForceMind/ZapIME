// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.util.concurrent.*;
/** Separate from both keyboard input and SQLite readers. Models never load on a UI thread. */
final class VoiceWorker {
    static volatile boolean nativeLoaded,voiceKeyLoaded;
    private static long cacheGeneration; // Accessed only on this executor.
    static void releaseWhenIdle(){long generation=++cacheGeneration;EXECUTOR.schedule(()->{if(generation==cacheGeneration)releaseCaches();},121,TimeUnit.SECONDS);}
    static void releaseCaches(){
        if(nativeLoaded)try{NativeVoice.release(0);}catch(RuntimeException|LinkageError ignored){}
        if(voiceKeyLoaded)try{NativeVoiceKey.releaseCache();}catch(RuntimeException|LinkageError ignored){}
    }
    static final ScheduledExecutorService EXECUTOR=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"zapime-voice");t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private VoiceWorker(){}
}
