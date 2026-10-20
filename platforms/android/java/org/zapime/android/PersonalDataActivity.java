// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

public final class PersonalDataActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private long handle;
    private boolean closed;
    private Spinner type;
    private EditText code,text,position;
    private TextView status;
    private LinearLayout rows;
    private JSONObject selected;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("个人资料");title.setTextSize(28);page.addView(title);
        TextView note=new TextView(this);note.setText("保存在本机，离线可用。云同步仍在开发；收藏不受临时剪贴板过期影响。长按键盘候选可置顶。");page.addView(note);
        status=new TextView(this);status.setText("正在打开本机资料…");page.addView(status);
        type=new Spinner(this);type.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"快捷短语","个人词库","候选置顶规则","永久收藏","本机剪贴板（保留7天）"}));page.addView(type);
        code=new EditText(this);code.setHint("编码，例如 wx / nihao；收藏可留空");code.setSingleLine(true);code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);code.setContentDescription("personal-code");page.addView(code);
        text=new EditText(this);text.setHint("正文");text.setMinLines(2);text.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);text.setContentDescription("personal-text");page.addView(text);
        position=new EditText(this);position.setHint("候选位置（1–40）");position.setInputType(2);position.setText("1");page.addView(position);
        Button save=new Button(this);save.setText("保存到本机");save.setOnClickListener(v->save());page.addView(save);
        Button clear=new Button(this);clear.setText("新建一条");clear.setOnClickListener(v->{selected=null;code.setText("");text.setText("");position.setText("1");});page.addView(clear);
        Button export=new Button(this);export.setText("导出明文备份");export.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("导出明文资料").setMessage("文件包含个人词库、短语、收藏和逻辑删除记录；任何拿到文件的人都可能阅读。请保存到自己控制的安全位置。此入口不导出账号凭据。").setNegativeButton("取消",null).setPositiveButton("选择保存位置",(d,w)->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"ZapIME-personal-data.json");startActivityForResult(intent,10);}).show());page.addView(export);
        Button restore=new Button(this);restore.setText("从备份恢复（仅空资料库）");restore.setOnClickListener(v->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,11);});page.addView(restore);
        rows=new LinearLayout(this);rows.setOrientation(1);page.addView(rows);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        worker.execute(()->{try {ResourceInstaller.prepare(this);handle=NativeCore.open(new File(getFilesDir(),"zapime").getAbsolutePath(),false);if(handle==0)throw new IOException("open_failed");refresh();}catch(Exception|LinkageError error){message("资料打开失败，请重新进入。");}});
    }
    private void message(String value){main.post(()->{if(!closed)status.setText(value);});}
    private JSONObject request(JSONObject input)throws Exception {if(handle==0)throw new IOException("not_ready");JSONObject result=new JSONObject(NativeCore.request(handle,input.toString()));if(result.has("error"))throw new IOException(result.optString("error"));return result;}
    private void refresh()throws Exception {
        JSONArray records=request(new JSONObject().put("op","personal_list")).getJSONArray("records");
        main.post(()->{if(closed)return;rows.removeAllViews();status.setText("已保存 "+records.length()+" 条 · 本机数据");
            for(int i=0;i<records.length();i++) {JSONObject record=records.optJSONObject(i);if(record==null)continue;JSONObject content=record.optJSONObject("content");if(content==null)continue;
                Button edit=new Button(this);edit.setAllCaps(false);edit.setText(content.optString("code",content.optString("title","收藏/剪贴板"))+"\n"+content.optString("text"));rows.addView(edit);edit.setOnClickListener(v->{selected=record;String kind=content.optString("type");type.setSelection(kind.equals("snippet")?0:kind.equals("dictionary")?1:kind.equals("candidate_rule")?2:kind.equals("favorite")?3:4);code.setText(content.optString("code"));text.setText(content.optString("text"));position.setText(content.optString("position","1"));});
                Button delete=new Button(this);delete.setText("删除此条");rows.addView(delete);delete.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("从有效资料中删除？备份与本地历史仍可能保留内容。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->worker.execute(()->{try{request(editRequest(record,content,true));refresh();}catch(Exception error){message("删除失败："+error.getMessage());}})).show());
            }
        });
    }
    private JSONObject editRequest(JSONObject record,JSONObject content,boolean deleted)throws JSONException {
        return new JSONObject().put("op","personal_edit").put("id",record==null?UUID.randomUUID().toString():record.getString("id")).put("operation_id",UUID.randomUUID().toString()).put("expected_revision",record==null?0:record.getLong("revision")).put("content",content).put("deleted",deleted);
    }
    private void save() {
        try {
            int index=type.getSelectedItemPosition();String body=text.getText().toString();String trigger=code.getText().toString().trim();JSONObject content=new JSONObject().put("text",body);
            if(index==0)content.put("type","snippet").put("code",trigger).put("tags",new JSONArray());
            if(index==1)content.put("type","dictionary").put("scheme","pinyin").put("code",trigger).put("weight",1000);
            if(index==2)content.put("type","candidate_rule").put("scheme","pinyin").put("code",trigger).put("position",Integer.parseInt(position.getText().toString()));
            if(index==3)content.put("type","favorite").put("title",trigger);
            if(index==4)content.put("type","clipboard").put("expires_at_ms",System.currentTimeMillis()+7L*86400000).put("pinned",false);
            final JSONObject input=editRequest(selected,content,false);worker.execute(()->{try{request(input);main.post(()->{selected=null;text.setText("");code.setText("");});refresh();}catch(Exception error){message("保存失败，请检查编码、位置和正文。错误："+error.getMessage());}});
        }catch(Exception error){message("请填写有效编码、正文和候选位置。");}
    }
    @Override protected void onActivityResult(int operation,int result,Intent intent) {
        super.onActivityResult(operation,result,intent);if(result!=RESULT_OK || intent==null || intent.getData()==null)return;final Uri uri=intent.getData();
        worker.execute(()->{try {
            if(operation==10){String archive=request(new JSONObject().put("op","personal_export")).getString("archive");try(OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new IOException("document_unavailable");output.write(archive.getBytes(StandardCharsets.UTF_8));}message("明文备份已保存。请妥善保管。");}
            if(operation==11){ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("document_unavailable");byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>32*1024*1024)throw new IOException("file_too_large");bytes.write(buffer,0,count);}}request(new JSONObject().put("op","personal_import").put("archive",new String(bytes.toByteArray(),StandardCharsets.UTF_8)));refresh();message("恢复完成，已验证归档内容。");}
        }catch(Exception error){message("文件操作未完成："+error.getMessage());}});
    }
    @Override public void onDestroy(){closed=true;worker.execute(()->{if(handle!=0)NativeCore.close(handle);});worker.shutdown();super.onDestroy();}
}
