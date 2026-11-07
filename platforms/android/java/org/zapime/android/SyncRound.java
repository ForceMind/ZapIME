// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.io.IOException;

/** One user-invoked, bounded round. No timer, retry, upload permission or UI. */
final class SyncRound {
    static final int MAX_UPLOADS=16,MAX_PULL_PAGES=2,PAGE_LIMIT=100;
    interface Backend {
        void check()throws IOException;
        void verifyIdentity()throws IOException;
        Page pull()throws IOException;
        byte[] stage()throws IOException;
        void pushAndAcknowledge(byte[] frozen)throws IOException;
    }
    static final class Page {
        final long cursor,head;
        final int applied,echoed,unchanged;
        Page(long cursor,long head,int applied,int echoed,int unchanged){this.cursor=cursor;this.head=head;this.applied=applied;this.echoed=echoed;this.unchanged=unchanged;}
    }
    static final class Result {
        int uploaded,applied,echoed,unchanged,pages;
        long cursor,head;
        boolean uploadBudgetReached,downloadBudgetReached;
    }
    static final class Failure extends IOException {
        final Result completed;
        Failure(Result completed,IOException cause){super("sync_round_incomplete",cause);this.completed=completed;}
    }
    private static boolean pull(Backend backend,Result result)throws IOException {
        for(int i=0;i<MAX_PULL_PAGES;i++){
            backend.check();Page page=backend.pull();
            if(page==null||page.cursor<0||page.head<page.cursor||page.applied<0||page.echoed<0||page.unchanged<0)throw new IOException("sync_round_page_invalid");
            result.pages++;result.applied+=page.applied;result.echoed+=page.echoed;result.unchanged+=page.unchanged;
            result.cursor=page.cursor;result.head=page.head;
            // A committed page may finish after cancellation or identity change.
            // Keep its counts, then reject an otherwise successful/limited return.
            backend.check();
            if(page.cursor==page.head)return true;
        }
        result.downloadBudgetReached=true;return false;
    }
    static Result run(Backend backend)throws Failure {
        Result result=new Result();
        try{
            backend.check();backend.verifyIdentity();
            // Catch up first. A bounded partial pull cannot silently declare
            // this device current or publish over unobserved remote changes.
            if(!pull(backend,result))return result;
            for(int i=0;i<MAX_UPLOADS;i++){
                backend.check();byte[] frozen=backend.stage();
                if(frozen==null)break;
                try{backend.check();backend.pushAndAcknowledge(frozen);result.uploaded++;}
                finally{java.util.Arrays.fill(frozen,(byte)0);}
                if(i==MAX_UPLOADS-1)result.uploadBudgetReached=true;
            }
            if(result.uploaded>0)pull(backend,result);
            backend.check();return result;
        }catch(IOException incomplete){throw new Failure(result,incomplete);}
    }
    private SyncRound(){}
}
