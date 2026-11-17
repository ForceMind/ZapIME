// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed content checks for a FUTURE on-device semantic rewrite model.
 * Never invokes a model, accesses the network, or commits editor text.
 * Passing is a necessary condition, not a guarantee that meaning is preserved.
 */
final class VoicePolishSafety {
    private static final int MAX_INPUT=2048, MAX_OUTPUT=2048;
    private static final Pattern NUMERIC=Pattern.compile("[+-]?[0-9]+(?:[.,:][0-9]+)*(?:[%％])?");
    private static final Pattern UNITS=Pattern.compile("[0-9]+(?:\\.[0-9]+)?\\s*(?:mg|mL|ml|µg|μg|ug|kg|g|IU|mmol/L|%)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern URL=Pattern.compile("(?:https?://|www\\.)[^\\s，。；]+",Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY=Pattern.compile("[$¥￥€£]");
    private static final Pattern DATED_UNIT=Pattern.compile("[0-9]+(?:年|月|日|号|时|分)");
    private static final Pattern EN_NEGATION=Pattern.compile("\\b(?:not|never|no|without|cannot|can't|don't|won't|shouldn't|mustn't|isn't)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL=Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern QUOTED=Pattern.compile("“[^”]{1,128}”|「[^」]{1,128}」|『[^』]{1,128}』|\\\"[^\\\"]{1,128}\\\"|\\x60[^\\x60]{1,128}\\x60");
    private static final String CHINESE_NUMERALS="零〇一二两三四五六七八九十百千万亿点";
    private static final String NEGATION_CHARS="不没无未别勿非";
    private VoicePolishSafety(){}

    enum Reason { ACCEPTED, INVALID, TOO_LONG, MODEL_MARKUP, NUMERIC_CHANGED,
        UNITS_CHANGED, NEGATION_CHANGED, CURRENCY_CHANGED, DATE_CHANGED,
        QUOTES_CHANGED, URL_CHANGED, EMAIL_CHANGED }

    private static List<String> matches(Pattern pattern,String s) {
        List<String> values=new ArrayList<>();
        Matcher matcher=pattern.matcher(s);
        while(matcher.find())values.add(matcher.group());
        return values;
    }
    private static List<String> codepointRuns(String input,String alphabet) {
        List<String> values=new ArrayList<>();
        StringBuilder run=new StringBuilder();
        for(int i=0;i<input.length();){
            int cp=input.codePointAt(i);i+=Character.charCount(cp);
            if(cp<=0xffff&&alphabet.indexOf(cp)>=0)run.appendCodePoint(cp);
            else if(run.length()>0){values.add(run.toString());run.setLength(0);}
        }
        if(run.length()>0)values.add(run.toString());
        return values;
    }
    private static boolean validUnicode(String text) {
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){
                if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))return false;
            }else if(Character.isLowSurrogate(c))return false;
            else if(Character.isISOControl(c)&&c!='\n'&&c!='\t')return false;
        }
        return true;
    }
    static Reason inspect(String original,String rewritten) {
        if(original==null||rewritten==null||original.isEmpty()||rewritten.trim().isEmpty()
                ||!validUnicode(original)||!validUnicode(rewritten))return Reason.INVALID;
        if(original.length()>MAX_INPUT||rewritten.length()>MAX_OUTPUT
                ||rewritten.length()*4<original.length()||rewritten.length()>original.length()*3/2)
            return Reason.TOO_LONG;
        if(rewritten.contains("<think>")||rewritten.contains("</think>")
                ||rewritten.contains("<|im_start|>")||rewritten.contains("<|im_end|>")
                ||rewritten.contains("\u0060\u0060\u0060"))return Reason.MODEL_MARKUP;
        if(!matches(NUMERIC,original).equals(matches(NUMERIC,rewritten))
                ||!codepointRuns(original,CHINESE_NUMERALS).equals(codepointRuns(rewritten,CHINESE_NUMERALS)))
            return Reason.NUMERIC_CHANGED;
        if(!matches(UNITS,original).equals(matches(UNITS,rewritten)))return Reason.UNITS_CHANGED;
        if(!matches(CURRENCY,original).equals(matches(CURRENCY,rewritten)))return Reason.CURRENCY_CHANGED;
        if(!matches(DATED_UNIT,original).equals(matches(DATED_UNIT,rewritten)))return Reason.DATE_CHANGED;
        if(!codepointRuns(original,NEGATION_CHARS).equals(codepointRuns(rewritten,NEGATION_CHARS))
                ||!matches(EN_NEGATION,original).equals(matches(EN_NEGATION,rewritten)))
            return Reason.NEGATION_CHANGED;
        if(!matches(QUOTED,original).equals(matches(QUOTED,rewritten)))return Reason.QUOTES_CHANGED;
        if(!matches(URL,original).equals(matches(URL,rewritten)))return Reason.URL_CHANGED;
        if(!matches(EMAIL,original).equals(matches(EMAIL,rewritten)))return Reason.EMAIL_CHANGED;
        return Reason.ACCEPTED;
    }
    /** Never returns the unchecked suggestion; the host keeps the original. */
    static String safePreview(String original,String proposed) {
        return inspect(original,proposed)==Reason.ACCEPTED?proposed:original;
    }
}
