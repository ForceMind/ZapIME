// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative, deterministic, fully offline post-ASR cleanup.
 *
 * This is not semantic summarization: never infer a missing word or reorder
 * clauses. Only a standalone filler followed by a comma, or adjacent identical
 * sufficiently long clauses, is removed. The caller must retain the original
 * and obtain explicit confirmation before committing either version.
 */
final class VoiceTextCleanup {
    private static final int MAX_CHARS=8192;
    private static final int MAX_PARTS=128;
    private static final int MIN_DUPLICATE_CODEPOINTS=6;

    private static final class Part {
        final String body;
        final char punctuation;
        Part(String body,char punctuation){this.body=body;this.punctuation=punctuation;}
        String normalized(){return body.trim();}
    }
    private VoiceTextCleanup(){}

    private static boolean boundary(char value){
        return value=='，'||value==','||value=='。'||value=='！'||value=='!'
                ||value=='？'||value=='?'||value=='；'||value==';'||value=='\n';
    }
    private static boolean pause(char value){return value=='，'||value==',';}
    private static boolean repeatedSeparator(char value){
        return pause(value)||value=='。'||value=='；'||value==';';
    }
    private static boolean filler(String value){
        switch(value){
            case "嗯":case "呃":case "额":case "嗯嗯":case "那个":
            case "就是说":return true;
            default:return false;
        }
    }
    private static boolean safeDuplicate(String value){
        if(value.codePointCount(0,value.length())<MIN_DUPLICATE_CODEPOINTS)return false;
        boolean han=false;
        for(int i=0;i<value.length();){
            int cp=value.codePointAt(i);
            i+=Character.charCount(cp);
            if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)han=true;
            // Never erase potentially meaningful repeated numbers, negation,
            // prohibitions, names in quotes, or time/quantity expressions.
            if(Character.isDigit(cp)||"零一二三四五六七八九十百千万两不没无别勿禁\"“”‘’".indexOf(cp)>=0)
                return false;
        }
        return han;
    }
    private static boolean duplicate(Part first,Part second){
        String a=first.normalized(),b=second.normalized();
        return repeatedSeparator(first.punctuation)
                &&a.equals(b)&&safeDuplicate(a);
    }

    /** Returns the original bytes/characters unchanged on any uncertainty. */
    static String concise(String original){
        if(original==null)return "";
        if(original.isEmpty()||original.length()>MAX_CHARS)return original;
        try{
            List<Part> pieces=new ArrayList<>();
            int start=0;
            for(int i=0;i<original.length();i++){
                if(boundary(original.charAt(i))){
                    if(pieces.size()>=MAX_PARTS)return original;
                    pieces.add(new Part(original.substring(start,i),original.charAt(i)));
                    start=i+1;
                }
            }
            if(start<original.length()){
                if(pieces.size()>=MAX_PARTS)return original;
                pieces.add(new Part(original.substring(start),'\0'));
            }
            if(pieces.isEmpty())return original;
            List<Part> kept=new ArrayList<>(pieces.size());
            for(Part piece:pieces){
                // A demonstrative followed by a noun is never a filler; a
                // standalone "那个，" is an explicit transcription pause.
                if(pause(piece.punctuation)&&filler(piece.normalized()))continue;
                kept.add(piece);
            }
            StringBuilder out=new StringBuilder(original.length());
            for(int i=0;i<kept.size();i++){
                Part part=kept.get(i);
                // Drop only a duplicate clause immediately before its exact
                // successor; keep the successor's original final punctuation.
                if(i+1<kept.size()&&duplicate(part,kept.get(i+1)))continue;
                out.append(part.body);
                if(part.punctuation!='\0')out.append(part.punctuation);
            }
            if(out.length()==0)return original;
            String result=out.toString();
            return result.equals(original)?original:result;
        }catch(RuntimeException malformed){
            return original;
        }
    }
}
