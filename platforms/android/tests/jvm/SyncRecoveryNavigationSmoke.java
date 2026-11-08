// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

public final class SyncRecoveryNavigationSmoke {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void reject(Runnable action){boolean refused=false;try{action.run();}catch(IllegalStateException expected){refused=true;}check(refused);}
    public static void main(String[] args){
        SyncRecoveryNavigation route=new SyncRecoveryNavigation();
        check(route.observe(true,true,true)==SyncRecoveryNavigation.Decision.SELECT_DOWNLOADS_ROOT);
        route.selectRoot();
        // Real failure shape: provider still has roots but loses the filename.
        // It must wait for the location, never repeat navigation or touch blindly.
        for(int n=0;n<150;n++)check(route.observe(false,false,true)==SyncRecoveryNavigation.Decision.WAIT_FOR_LOCATION);
        reject(route::selectRoot);reject(()->route.touchDocument(false,false));
        check(route.observe(true,true,true)==SyncRecoveryNavigation.Decision.WAIT_FOR_LOCATION);
        reject(()->route.touchDocument(true,true));
        // After a normal root choice a persistent sidebar does not cover the
        // target point: an actual visible file can stabilize and be touched once.
        check(route.observe(true,false,true)==SyncRecoveryNavigation.Decision.STABILIZE_FILENAME);
        route.touchDocument(true,false);reject(()->route.touchDocument(true,false));
        check(route.rootSelections()==1);
        SyncRecoveryNavigation alreadyAtFile=new SyncRecoveryNavigation();
        check(alreadyAtFile.observe(true,false,false)==SyncRecoveryNavigation.Decision.STABILIZE_FILENAME);
        alreadyAtFile.touchDocument(true,false);check(alreadyAtFile.rootSelections()==0);
        SyncRecoveryNavigation unavailable=new SyncRecoveryNavigation();
        check(unavailable.observe(false,false,false)==SyncRecoveryNavigation.Decision.WAIT_FOR_LOCATION);
        check(unavailable.observe(true,true,false)==SyncRecoveryNavigation.Decision.WAIT_FOR_LOCATION);
        reject(()->unavailable.touchDocument(true,true));
        System.out.println("PASS observed root navigation: missing filename, cover, persistent sidebar and one-touch ownership");
    }
}
