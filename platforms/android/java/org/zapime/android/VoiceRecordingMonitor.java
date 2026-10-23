// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import java.util.List;

/** Observes this recorder only; never tries to override system capture policy. */
interface VoiceRecordingMonitor extends AutoCloseable {
    void check();
    @Override void close();

    static VoiceRecordingMonitor create(AudioRecord recorder,Runnable silenced){
        if(Build.VERSION.SDK_INT>=29)return new Api29(recorder,silenced);
        return new VoiceRecordingMonitor(){public void check(){}public void close(){}};
    }

    final class Api29 implements VoiceRecordingMonitor {
        private final AudioRecord recorder;
        private final VoiceRecordingState state;
        private final AudioManager.AudioRecordingCallback callback=new AudioManager.AudioRecordingCallback(){
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations){
                for(AudioRecordingConfiguration configuration:configurations)observe(configuration);
            }
        };

        Api29(AudioRecord recorder,Runnable silenced){
            this.recorder=recorder;state=new VoiceRecordingState(recorder.getAudioSessionId(),silenced);
            // Register before startRecording. Callback does only bounded state changes.
            recorder.registerAudioRecordingCallback(Runnable::run,callback);
        }
        private void observe(AudioRecordingConfiguration configuration){
            if(configuration!=null)state.observe(configuration.getClientAudioSessionId(),configuration.isClientSilenced());
        }
        @Override public void check(){observe(recorder.getActiveRecordingConfiguration());}
        @Override public void close(){state.close();recorder.unregisterAudioRecordingCallback(callback);}
    }
}
