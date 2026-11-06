// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
/** Session-local preview: absence is not an explicit empty recognition result. */
final class VoicePreviewState {
    private String text="";
    String accept(boolean present,String value){if(present&&value!=null)text=value;return text;}
    String text(){return text;}
}
