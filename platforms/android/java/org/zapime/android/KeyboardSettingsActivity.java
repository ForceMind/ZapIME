// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.*;

/** Device-local presentation and feedback preferences, never synchronized. */
public final class KeyboardSettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs=getSharedPreferences("keyboard",MODE_PRIVATE);
        LinearLayout page=Ui.page(this);
        page.addView(Ui.text(this,"键盘与反馈",28,Ui.INK));
        page.addView(Ui.text(this,"下次打开键盘时生效 · 设置仅保存在本机",13,Ui.MUTED));
        LinearLayout appearance=Ui.card(page);appearance.addView(Ui.text(this,"键盘外观",20,Ui.INK));
        RadioGroup themes=new RadioGroup(this);
        String[] labels={"跟随系统","浅色","深色"},values={"system","light","dark"};
        for(int i=0;i<labels.length;i++) {
            RadioButton b=new RadioButton(this);b.setId(i+100);b.setText(labels[i]);b.setTextColor(Ui.INK);b.setContentDescription("键盘主题 "+values[i]);themes.addView(b);
            if(values[i].equals(prefs.getString("theme","system")))themes.check(b.getId());
        }
        themes.setOnCheckedChangeListener((group,id)->prefs.edit().putString("theme",values[id-100]).apply());appearance.addView(themes);
        LinearLayout feedback=Ui.card(page);feedback.addView(Ui.text(this,"按键反馈",20,Ui.INK));
        Switch haptic=new Switch(this);haptic.setText("轻触震动");haptic.setTextColor(Ui.INK);haptic.setContentDescription("按键震动开关");haptic.setChecked(prefs.getBoolean("haptic",true));haptic.setPadding(0,Ui.dp(this,16),0,Ui.dp(this,16));haptic.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("haptic",on).apply());feedback.addView(haptic);
        feedback.addView(Ui.text(this,"遵循系统触感设置；实际手感取决于手机马达。长按退格连续删除，移开手指即停止。",13,Ui.MUTED));
        Switch sound=new Switch(this);sound.setText("按键声音");sound.setTextColor(Ui.INK);sound.setContentDescription("按键声音开关");sound.setChecked(prefs.getBoolean("sound",false));sound.setPadding(0,Ui.dp(this,16),0,Ui.dp(this,16));sound.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("sound",on).apply());feedback.addView(sound);
        LinearLayout ranking=Ui.card(page);ranking.addView(Ui.text(this,"中文候选",20,Ui.INK));
        Switch enhance=new Switch(this);enhance.setText("离线候选增强");enhance.setTextColor(Ui.INK);enhance.setContentDescription("离线候选增强开关");enhance.setChecked(CandidateRankingPreferences.enabled(this));enhance.setPadding(0,Ui.dp(this,16),0,Ui.dp(this,16));enhance.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean(CandidateRankingPreferences.KEY,on).apply());ranking.addView(enhance);
        ranking.addView(Ui.text(this,"默认开启，在手机上调整精确拼音候选的顺序。基础候选先显示，模型不可用时仍可正常输入；个人置顶、补全和部分选词保持原规则。",13,Ui.MUTED));
        ranking.addView(Ui.text(this,"仅使用当前输入框的有限上下文，发送、切换或位置不确定时清除。隐私输入和搜索不启用增强；输入内容不上传、不保存为聊天历史。",13,Ui.MUTED));
        LinearLayout info=Ui.card(page);info.addView(Ui.text(this,"当前能力",20,Ui.INK));info.addView(Ui.text(this,"九键 / 全键拼音、英文、符号、表情、光标编辑、本机资料与备份。\n导入配套模型并允许麦克风后，可长按空格使用离线语音，确认后插入。跨设备同步仍在开发。",14,Ui.MUTED));
        Button back=Ui.button(this,"返回",Ui.BLUE,true);back.setOnClickListener(v->finish());Ui.addButton(page,back);Ui.showPage(this,page);
    }
}
