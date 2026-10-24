// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/** Conservative declared-field allowlist; no target-app text or extras are read. */
final class EnglishLearningEligibility {
    static boolean allows(EditorInfo info, boolean privateSession) {
        if (info == null || privateSession
                || (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                || (info.imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH
                || (info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
                || (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_NORMAL
                || variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
                || variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE;
    }
    private EnglishLearningEligibility() {}
}
