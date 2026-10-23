// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.content.res.Configuration;

/** Keyboard palettes are instance-local; Activity colors are not mutated. */
final class KeyboardTheme {
    final boolean dark;
    final int deck, toolbar, key, special, ink, muted, accent, stroke, ripple;
    private KeyboardTheme(boolean dark) {
        this.dark=dark;
        deck=dark?0xff171719:0xffe5e6ed;
        toolbar=dark?0xff2c2c2f:0xfffafbfe;
        key=dark?0xff4d4d50:0xffffffff;
        special=dark?0xff2d2d30:0xffbdc6d4;
        ink=dark?0xfff5f5f7:0xff303741;
        muted=dark?0xffb9b9c1:0xff626e7e;
        accent=dark?0xff99bfff:0xff2366cb;
        stroke=dark?0xff555559:0xffcfd3dc;
        ripple=dark?0x38ffffff:0x202b4267;
    }
    static KeyboardTheme load(Context c) {
        String mode=c.getSharedPreferences("keyboard",Context.MODE_PRIVATE).getString("theme","system");
        boolean night=(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        return new KeyboardTheme(mode.equals("dark") || (mode.equals("system")&&night));
    }
}
