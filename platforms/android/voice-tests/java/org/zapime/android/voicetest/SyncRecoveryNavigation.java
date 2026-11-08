// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

/** One observed root control action, followed by one unoccluded document touch.
 * A roots container can remain visible as a sidebar: its existence is not cover.
 */
final class SyncRecoveryNavigation {
    enum Decision { SELECT_DOWNLOADS_ROOT, WAIT_FOR_LOCATION, STABILIZE_FILENAME }
    private boolean rootSelected,touched;

    Decision observe(boolean documentPresent,boolean documentCovered,boolean downloadsControlAvailable){
        if(downloadsControlAvailable&&!rootSelected)return Decision.SELECT_DOWNLOADS_ROOT;
        return documentPresent&&!documentCovered?Decision.STABILIZE_FILENAME:Decision.WAIT_FOR_LOCATION;
    }
    void selectRoot(){
        if(rootSelected||touched)throw new IllegalStateException("recovery_navigation_repeated_action");
        rootSelected=true;
    }
    void touchDocument(boolean documentPresent,boolean covered){
        if(touched||!documentPresent||covered)throw new IllegalStateException("recovery_navigation_unsafe_touch");
        touched=true;
    }
    int rootSelections(){return rootSelected?1:0;}
}
