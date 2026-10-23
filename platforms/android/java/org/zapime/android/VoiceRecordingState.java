// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

/** One recorder's terminal policy event. close drains an already running event. */
final class VoiceRecordingState {
    private final int session;
    private final Runnable silenced;
    private boolean active=true,reported;
    VoiceRecordingState(int session,Runnable silenced){this.session=session;this.silenced=silenced;}
    synchronized void observe(int changedSession,boolean muted){
        if(active&&!reported&&changedSession==session&&muted){reported=true;silenced.run();}
    }
    synchronized void close(){active=false;}
}
