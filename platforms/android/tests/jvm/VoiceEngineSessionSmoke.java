// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class VoiceEngineSessionSmoke {
    static void check(boolean value){if(!value)throw new AssertionError("engine routing");}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("zapime-engine-route");int checks=0;
        try{
            for(int state=0;state<4;state++){
                boolean x=(state&1)!=0,v=(state&2)!=0;
                for(String chosen:new String[]{"xasr","voicekey"}){
                    VoiceEngineSession session=new VoiceEngineSession(chosen);AtomicInteger xc=new AtomicInteger(),vc=new AtomicInteger();
                    boolean ready=session.available(()->{xc.incrementAndGet();return x;},()->{vc.incrementAndGet();return v;});
                    check(ready==(session.voiceKey()?v:x));check(xc.get()==(session.voiceKey()?0:1)&&vc.get()==(session.voiceKey()?1:0));
                    File wanted=root.resolve(chosen).toFile();
                    File verified=session.verified(()->{if(session.voiceKey())throw new AssertionError("wrong model");return wanted;},()->{if(!session.voiceKey())throw new AssertionError("wrong model");return wanted;});
                    check(verified.equals(wanted));checks++;
                }
            }
            for(String chosen:new String[]{"xasr","voicekey"}){
                VoiceEngineSession session=new VoiceEngineSession(chosen);check(session.available(()->true,()->true));
                try{session.verified(()->{if(!session.voiceKey())throw new IOException("corrupt selected");throw new AssertionError("unselected verifier");},()->{if(session.voiceKey())throw new IOException("corrupt selected");throw new AssertionError("unselected verifier");});throw new AssertionError("corrupt model accepted");}catch(IOException expected){check(expected.getMessage().equals("corrupt selected"));}checks++;
            }
            check(new VoiceEngineSession("corrupt preference").engine.equals("xasr"));checks++;
            VoicePreviewState preview=new VoicePreviewState();check(preview.text().isEmpty());
            check(preview.accept(true,"synthetic partial").equals("synthetic partial"));
            for(int n=0;n<20;n++)check(preview.accept(false,null).equals("synthetic partial"));
            check(preview.accept(true,"").isEmpty());check(new VoicePreviewState().text().isEmpty());checks++;
            AtomicLong clock=new AtomicLong(1000);VoiceStopBudget budget=new VoiceStopBudget(clock::get);
            clock.set(900000);check(budget.remainingMillis()==30000&&!budget.expired());check(budget.stop());
            for(int n=1;n<=3;n++){clock.addAndGet(9000);check(!budget.stop());check(budget.remainingMillis()==30000-n*9000);}
            clock.addAndGet(3000);check(budget.expired()&&budget.remainingMillis()==0&&!budget.stop());checks++;
            VoiceStopBudget fresh=new VoiceStopBudget(clock::get);check(!fresh.expired()&&fresh.remainingMillis()==30000);check(fresh.stop());clock.addAndGet(30001);check(fresh.expired()&&budget.expired());checks++;
            System.out.println("PASS immutable engine routing, preview and shared stop budget: "+checks+" cases; X-ASR-only/VoiceKey-only/both/none/corrupt selected; no fallback. Not model decoding.");
        }finally{Files.delete(root);}
    }
}
