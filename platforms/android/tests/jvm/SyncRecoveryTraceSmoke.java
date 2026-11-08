// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

public final class SyncRecoveryTraceSmoke {
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    public static void main(String[] args){
        SyncRecoveryTrace trace=new SyncRecoveryTrace();
        trace.step=SyncRecoveryTrace.Step.FILENAME_POLL;
        trace.observed(SyncRecoveryTrace.Window.DOCUMENTS_UI,true,true);
        trace.closeAttempt();
        check("navigation_close_request".equals(trace.stepCode()),"close request is not observed dismissal");
        trace.step=SyncRecoveryTrace.Step.NAVIGATION_CLOSE_WAIT;
        for(int n=0;n<200;n++)trace.observed(SyncRecoveryTrace.Window.DOCUMENTS_UI,true,true);
        check(trace.polls==150&&trace.closeAttempts==1&&trace.touchAttempts==0,"drawer wait lost bounded pre-touch evidence");
        check("navigation_close_wait".equals(trace.stepCode())&&"documents_ui".equals(trace.windowCode()),"drawer failure code");
        trace.observed(SyncRecoveryTrace.Window.PRODUCT,false,false);
        check("product".equals(trace.windowCode())&&!trace.rootsPresent&&!trace.filenamePresent,"unexpected provider exit must be distinguishable");
        trace.observed(SyncRecoveryTrace.Window.DOCUMENTS_UI,false,false);
        trace.step=SyncRecoveryTrace.Step.FILENAME_POLL;
        check("filename_poll".equals(trace.stepCode()),"missing filename must be distinguishable from drawer");
        trace.observed(SyncRecoveryTrace.Window.DOCUMENTS_UI,false,true);trace.touchAttempt();
        trace.step=SyncRecoveryTrace.Step.TOUCH_UP;trace.step=SyncRecoveryTrace.Step.PROVIDER_RETURN_WAIT;
        check(trace.touchAttempts==1&&"provider_return_wait".equals(trace.stepCode()),"accepted touch is not provider return");
        for(SyncRecoveryTrace.Step step:SyncRecoveryTrace.Step.values())check(step.name().matches("[A-Z_]+"),"dynamic substep");
        for(SyncRecoveryTrace.Window window:SyncRecoveryTrace.Window.values())check(window.name().matches("[A-Z_]+"),"dynamic window");
        System.out.println("PASS bounded content-free recovery provider failure trace");
    }
}
