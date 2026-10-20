// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.test;
import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(1);
        EditText normal=new EditText(this); normal.setHint("Synthetic input probe"); normal.setContentDescription("probe-normal"); normal.setInputType(InputType.TYPE_CLASS_TEXT); panel.addView(normal);
        EditText password=new EditText(this); password.setHint("Synthetic private probe"); password.setContentDescription("probe-password"); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); panel.addView(password);
        setContentView(panel); normal.requestFocus();
    }
}
