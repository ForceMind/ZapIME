// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

/** Device-local inference preference; neither context nor this switch is synced. */
final class CandidateRankingPreferences {
    static final String KEY = "candidate_ranking_enabled";

    static boolean enabled(Context context) {
        return context.getSharedPreferences("keyboard", Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static boolean eligible(EditorInfo info, boolean privateSession) {
        if (info == null || privateSession) return false;
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                && (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
                && (info.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_SEARCH
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    }

    private CandidateRankingPreferences() { }
}
