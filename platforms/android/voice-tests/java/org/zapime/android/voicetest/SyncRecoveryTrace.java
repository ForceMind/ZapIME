// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

/** Fixed, content-free diagnostics for one synthetic recovery provider selection.
 * No Exception message, node text, URI, package name, code or key is retained.
 */
final class SyncRecoveryTrace {
    enum Step { CASE_BODY, FILENAME_POLL, NAVIGATION_SNAPSHOT, NAVIGATION_CLOSE_REQUEST,
        NAVIGATION_CLOSE_WAIT, ROOT_CONTROL_SELECTION, FILE_SNAPSHOT, TOUCH_DOWN, TOUCH_UP,
        PROVIDER_RETURN_WAIT, SELECTED_DOCUMENT_READ, SELECTED_DOCUMENT_IDENTITY }
    enum Window { NONE, DOCUMENTS_UI, PRODUCT, OTHER }
    Step step=Step.CASE_BODY;
    Window window=Window.NONE;
    boolean rootsPresent,filenamePresent;
    int polls,closeAttempts,touchAttempts;

    void observed(Window value,boolean roots,boolean filename){
        window=value;rootsPresent=roots;filenamePresent=filename;
        if(polls<150)polls++;
    }
    void closeAttempt(){closeAttempts=1;step=Step.NAVIGATION_CLOSE_REQUEST;}
    void touchAttempt(){touchAttempts=1;step=Step.TOUCH_DOWN;}
    String stepCode(){return step.name().toLowerCase(java.util.Locale.ROOT);}
    String windowCode(){return window.name().toLowerCase(java.util.Locale.ROOT);}
}
