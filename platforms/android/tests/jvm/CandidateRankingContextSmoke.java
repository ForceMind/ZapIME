// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

public final class CandidateRankingContextSmoke {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static String repeat(String value,int count) { StringBuilder text=new StringBuilder();for(int i=0;i<count;i++)text.append(value);return text.toString(); }

    public static void main(String[] args) {
        CandidateRankingContext context=new CandidateRankingContext();
        context.begin(true,"本轮合成",4,4);
        check(context.active()&&context.text().equals("本轮合成"));
        long initial=context.version();
        context.committed("输入",true,true);
        check(context.text().equals("本轮合成输入")&&context.version()>initial);
        context.committed("未接受",false,true);
        check(context.text().isEmpty());

        context.committed("新的合成",true,true);
        check(context.text().equals("新的合成"));
        context.committed("保存的资料",true,false);
        check(context.text().isEmpty());
        context.committed("下一段",true,true);
        context.committed("\n",true,true);
        check(context.text().isEmpty());
        context.committed("新消息",true,true);
        context.clear();
        check(context.text().isEmpty());

        context.begin(true,"前一行\n当前行",7,7);
        check(context.text().equals("当前行"));
        context.begin(true,repeat("甲",70),70,70);
        check(context.text().equals(repeat("甲",64)));
        context.committed("乙",true,true);
        check(context.text().equals(repeat("甲",63)+"乙"));
        context.begin(true,repeat("😀",64),128,128);
        context.committed("😀",true,true);
        check(context.text().codePointCount(0,context.text().length())==64);
        check(context.text().length()==128&&CandidateRankingContext.validUtf16(context.text()));
        context.begin(true,repeat("甲",129),129,129);
        check(context.text().isEmpty()); // Reject oversized supplied snapshots.
        context.begin(true,"\udc00",1,1);
        check(context.text().isEmpty());
        context.committed("\ud800",true,true);
        check(context.text().isEmpty());

        context.begin(false,"隐私合成",4,4);
        context.committed("内容",true,true);
        check(!context.active()&&context.text().isEmpty());
        context.begin(true,"选区",0,2);
        context.committed("替换",true,true);
        check(!context.active()&&context.text().isEmpty());
        context.begin(true,"未知",-1,-1);
        check(!context.active()&&context.text().isEmpty());
        context.rebase(true,3,3);
        context.committed("重新确认",true,true);
        check(context.text().equals("重新确认"));
        long editor=context.version();
        context.begin(true,null,0,0);
        check(context.version()>editor&&context.text().isEmpty());
        context.committed("同字",true,true);
        long first=context.version();
        context.begin(true,"同字",2,2);
        check(context.version()>first); // Identical text in a different editor is new.
        context.suspend();
        context.committed("隐藏后回包",true,true);
        check(!context.active()&&context.text().isEmpty());
        System.out.println("Candidate ranking context smoke passed");
    }
}
