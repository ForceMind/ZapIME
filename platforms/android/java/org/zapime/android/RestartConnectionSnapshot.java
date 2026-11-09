// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.os.Looper;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

/** API31 references are never resolved by the API28 path. */
final class RestartConnectionSnapshot {
    static VerifiedRestart.Snapshot read(InputConnection connection,VerifiedRestart.Expected expected){
        if(Looper.myLooper()==Looper.getMainLooper()
                ||!Thread.currentThread().getName().equals("zapime-restart-verification"))
            throw new IllegalStateException("restart_reader_thread");
        SurroundingText value=connection.getSurroundingText(expected.beforeUnits(),VerifiedRestart.MAX_AFTER,0);
        if(value==null)return null;
        CharSequence text=value.getText();
        if(text==null||text.length()>VerifiedRestart.MAX_BEFORE+VerifiedRestart.MAX_AFTER)return null;
        return new VerifiedRestart.Snapshot(text.toString(),value.getOffset(),value.getSelectionStart(),value.getSelectionEnd());
    }
}
