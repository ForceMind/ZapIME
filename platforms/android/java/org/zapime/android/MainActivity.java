// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=Ui.page(this);
        TextView wordmark=Ui.text(this,"ZAPIME  /  闪电输入法",13,Ui.BLUE);wordmark.setLetterSpacing(0.08f);page.addView(wordmark);
        TextView title=Ui.text(this,"让输入，\n轻快一点。",34,Ui.INK);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setPadding(0,Ui.dp(this,18),0,Ui.dp(this,8));page.addView(title);
        page.addView(Ui.text(this,"一个输入法，连接所有设备",15,Ui.MUTED));
        LinearLayout setup=Ui.card(page);setup.addView(Ui.text(this,"开始使用",20,Ui.INK));TextView hint=Ui.text(this,"两步启用，随时开始离线输入。",14,Ui.MUTED);hint.setPadding(0,Ui.dp(this,6),0,0);setup.addView(hint);
        Button enable=Ui.button(this,"1  启用闪电输入法",Ui.BLUE,true);enable.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));Ui.addButton(setup,enable);
        Button choose=Ui.button(this,"2  切换到闪电输入法",Ui.BG,false);choose.setOnClickListener(v->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());Ui.addButton(setup,choose);
        LinearLayout test=Ui.card(page);test.addView(Ui.text(this,"试着打个招呼",18,Ui.INK));EditText editor=new EditText(this);editor.setHint("输入 nihao，选一个「你好」");editor.setMinLines(2);Ui.field(editor);test.addView(editor);
        LinearLayout data=Ui.card(page);data.addView(Ui.text(this,"我的资料",20,Ui.INK));data.addView(Ui.text(this,"词库、短语与收藏，保存在你手中。",14,Ui.MUTED));Button personal=Ui.button(this,"管理资料与备份  →",Ui.BG,false);personal.setContentDescription("个人资料 · 离线管理与备份");personal.setOnClickListener(v->startActivity(new Intent(this,PersonalDataActivity.class)));Ui.addButton(data,personal);
        TextView note=Ui.text(this,"开发验证版 0.1.0 · 当前为本机离线模式\n跨设备同步与云剪贴板仍在开发。",12,Ui.MUTED);note.setPadding(0,Ui.dp(this,20),0,Ui.dp(this,4));page.addView(note);
        Button legal = Ui.button(this,"",Ui.BG,false); legal.setText("开源许可与来源"); legal.setOnClickListener(v -> {
            try (java.io.InputStream in = getAssets().open("NOTICE.txt")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) != -1) bytes.write(buffer,0,count); String text = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                TextView view = new TextView(this); view.setText(text); view.setPadding(24,16,24,16); ScrollView scroll = new ScrollView(this); scroll.addView(view);
                new android.app.AlertDialog.Builder(this).setTitle("许可与来源").setView(scroll).setPositiveButton("关闭",null).show();
            } catch (Exception e) { new android.app.AlertDialog.Builder(this).setMessage("许可位于安装包 assets/licenses 中。").setPositiveButton("关闭",null).show(); }
        }); Ui.addButton(page,legal); Ui.showPage(this,page);
    }
}
