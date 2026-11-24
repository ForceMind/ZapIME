// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Browser App Link dispatches to the private Activity that owns the pending login. */
public final class AccountLoginCallbackActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent reply = getIntent();
        AccountLoginProtocol.Config config = AccountLoginDeployment.config();
        if (config != null && reply != null && Intent.ACTION_VIEW.equals(reply.getAction())
                && reply.getDataString() != null && reply.getDataString().startsWith(config.callback + "?")) {
            Intent target = new Intent(this, AccountSyncActivity.class).setAction(Intent.ACTION_VIEW).setData(reply.getData());
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(target);
        }
        finish();
    }
}
