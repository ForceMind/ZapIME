// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.io.File;
import java.util.function.BooleanSupplier;

/** One immutable engine choice from entry through trusted worker-side verification. */
final class VoiceEngineSession {
    static final String XASR="xasr", VOICEKEY="voicekey";
    interface Verifier { File verified() throws Exception; }
    final String engine;
    VoiceEngineSession(String engine){this.engine=VOICEKEY.equals(engine)?VOICEKEY:XASR;}
    boolean voiceKey(){return VOICEKEY.equals(engine);}
    boolean available(BooleanSupplier xasr,BooleanSupplier voicekey){return (voiceKey()?voicekey:xasr).getAsBoolean();}
    File verified(Verifier xasr,Verifier voicekey)throws Exception{return (voiceKey()?voicekey:xasr).verified();}
}
