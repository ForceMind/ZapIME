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
        LinearLayout page = new LinearLayout(this); page.setOrientation(1); page.setPadding(32,48,32,24);
        TextView title = new TextView(this); title.setText("⚡ 闪电输入法\nZapIME"); title.setTextSize(30); page.addView(title);
        TextView note = new TextView(this); note.setText("一个输入法，连接所有设备\n\n开发验证版 0.1.0\n当前提供离线拼音与英文；四端同步、云剪贴板和恢复功能尚在开发。此包不申请网络权限。\n\n首次准备词库可能需要数秒。输入法启用后可在下方测试。"); note.setTextSize(16); page.addView(note);
        Button enable = new Button(this); enable.setText("1 · 启用闪电输入法"); enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))); page.addView(enable);
        Button choose = new Button(this); choose.setText("2 · 切换到闪电输入法"); choose.setOnClickListener(v -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker()); page.addView(choose);
        EditText editor = new EditText(this); editor.setHint("在此输入 nihao，选择候选"); editor.setMinLines(3); page.addView(editor);
        Button personal = new Button(this); personal.setText("个人资料 · 离线管理与备份"); personal.setOnClickListener(v -> startActivity(new Intent(this,PersonalDataActivity.class))); page.addView(personal);
        Button legal = new Button(this); legal.setText("开源许可与来源"); legal.setOnClickListener(v -> {
            try (java.io.InputStream in = getAssets().open("NOTICE.txt")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count; while ((count = in.read(buffer)) != -1) bytes.write(buffer,0,count); String text = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                TextView view = new TextView(this); view.setText(text); view.setPadding(24,16,24,16); ScrollView scroll = new ScrollView(this); scroll.addView(view);
                new android.app.AlertDialog.Builder(this).setTitle("许可与来源").setView(scroll).setPositiveButton("关闭",null).show();
            } catch (Exception e) { new android.app.AlertDialog.Builder(this).setMessage("许可位于安装包 assets/licenses 中。").setPositiveButton("关闭",null).show(); }
        }); page.addView(legal); ScrollView pageScroll = new ScrollView(this); pageScroll.addView(page); setContentView(pageScroll);
    }
}
