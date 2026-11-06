// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoiceSettingsActivity extends Activity {
    private TextView status;
    private Button choose,chooseVoiceKey,useNative,useVoiceKey;
    private TextView voiceKeyStatus,engineInfo;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private boolean busy;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);LinearLayout page=Ui.page(this);
        page.addView(Ui.text(this,"离线语音",30,Ui.INK));
        page.addView(Ui.text(this,"录音和识别都在这台设备完成。\n录音不保存、不上传，识别后由你确认插入。",15,Ui.MUTED));
        LinearLayout model=Ui.card(page);model.addView(Ui.text(this,"中文与英文 · X-ASR",20,Ui.INK));model.addView(Ui.text(this,"2026-06-05 · 流式 INT8",12,Ui.MUTED));
        model.addView(Ui.text(this,"首次使用需导入配套模型 ZIP（解压约 169 MB）。仅接受本版本固定校验的模型；更换服务器不会影响已安装的离线模型。",14,Ui.MUTED));
        status=Ui.text(this,VoiceModels.installed(this)?"模型已安装 · 录音前会重新校验":VoiceModels.recoverable(this)?"检测到未完成导入 · 录音前将校验并尝试恢复":"尚未安装模型",14,Ui.BLUE);model.addView(status);
        choose=Ui.button(this,"导入离线模型 ZIP",Ui.BLUE,true);choose.setContentDescription("导入离线语音模型");choose.setOnClickListener(v->{if(busy)return;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");try{startActivityForResult(intent,1);}catch(ActivityNotFoundException e){status.setText("此设备没有文件选择器");}});Ui.addButton(model,choose);
        LinearLayout engines=Ui.card(page);
        engines.addView(Ui.text(this,"识别引擎",20,Ui.INK));
        engineInfo=Ui.text(this,"",14,Ui.MUTED);engines.addView(engineInfo);
        useNative=Ui.button(this,"使用 X-ASR（原有离线引擎）",Ui.BG,false);
        useNative.setOnClickListener(v->{if(busy)return;VoiceEnginePreferences.select(this,VoiceEnginePreferences.XASR);updateEngines();});
        Ui.addButton(engines,useNative);
        useVoiceKey=Ui.button(this,"使用 VoiceKey 微信离线（实验）",Ui.BG,false);
        useVoiceKey.setOnClickListener(v->{
            if(busy||!VoiceKeyModels.installed(this))return;
            busy=true;useVoiceKey.setEnabled(false);voiceKeyStatus.setText("正在校验 VoiceKey 模型…");
            Context app=getApplicationContext();
            VoiceWorker.EXECUTOR.execute(()->{
                boolean valid=false;
                try{VoiceKeyModels.verified(app,cancelled::get);valid=true;}catch(Exception ignored){}
                final boolean ok=valid;
                runOnUiThread(()->{
                    if(cancelled.get()||isFinishing())return;
                    busy=false;
                    if(ok)VoiceEnginePreferences.select(this,VoiceEnginePreferences.VOICEKEY);
                    updateEngines();
                    if(!ok)voiceKeyStatus.setText("VoiceKey 模型校验失败，请重新导入。原有 X-ASR 不受影响。");
                });
            });
        });Ui.addButton(engines,useVoiceKey);
        engines.addView(Ui.text(this,"VoiceKey 模型需要你从合法来源取得并手动选择原始离线语音包。安装过程只在本机运行，不包含第三方云接口。",13,Ui.MUTED));
        voiceKeyStatus=Ui.text(this,"",14,Ui.BLUE);engines.addView(voiceKeyStatus);
        chooseVoiceKey=Ui.button(this,"导入 VoiceKey 微信离线语音包",Ui.BLUE,true);
        chooseVoiceKey.setOnClickListener(v->{
            if(busy)return;
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try{startActivityForResult(intent,3);}catch(ActivityNotFoundException e){voiceKeyStatus.setText("此设备没有文件选择器");}
        });Ui.addButton(engines,chooseVoiceKey);
        updateEngines();
        Button permission=Ui.button(this,"允许使用麦克风",Ui.BG,false);permission.setOnClickListener(v->{if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){status.setText("麦克风已允许 · 只在显式开始录音后使用");return;}requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},2);});Ui.addButton(page,permission);
        Button legal=Ui.button(this,"模型与语音组件许可",Ui.BG,false);legal.setOnClickListener(v->showLicenses());Ui.addButton(page,legal);
        Button download=Ui.button(this,"查看项目与模型下载说明",Ui.BG,false);download.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/ForceMind/ZapIME/blob/main/docs/VOICE_INPUT.md")));}catch(ActivityNotFoundException e){status.setText("请在浏览器打开 github.com/ForceMind/ZapIME");}});Ui.addButton(page,download);
        page.addView(Ui.text(this,"安装后，长按键盘空格开始语音；轻点仍是空格与选词。也可在键盘工具中打开离线语音。\n设备性能会影响首次加载和识别速度。当前为开发验证功能，模型下载后可断网使用。",12,Ui.MUTED));
        Ui.showPage(this,page);
    }
    private void updateEngines(){
        if(engineInfo==null||voiceKeyStatus==null)return;
        String selected=VoiceEnginePreferences.selected(this);
        boolean ready=VoiceKeyModels.installed(this);
        engineInfo.setText("当前引擎："+(VoiceEnginePreferences.VOICEKEY.equals(selected)?"VoiceKey 微信离线（实验）":"X-ASR · 离线"));
        voiceKeyStatus.setText(ready?"VoiceKey 模型已导入；开始录音时会重新验证完整性。":"VoiceKey 模型尚未安装 · 原有 X-ASR 完全不受影响。");
        useVoiceKey.setEnabled(!busy&&ready);
        chooseVoiceKey.setEnabled(!busy);
        useNative.setEnabled(!busy);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if((request!=1&&request!=3)||result!=RESULT_OK||data==null||data.getData()==null||busy)return;
        final boolean voiceKey=request==3;
        final android.net.Uri uri=data.getData();final Button button=voiceKey?chooseVoiceKey:choose;
        busy=true;button.setEnabled(false);if(voiceKey)voiceKeyStatus.setText("正在校验并安装 VoiceKey 模型…");else status.setText("正在校验并安装模型…");
        Context context=getApplicationContext();
        VoiceWorker.EXECUTOR.execute(()->{
            boolean success=false;
            try(InputStream in=context.getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException();
                if(voiceKey)VoiceKeyModels.importPackage(context,in,cancelled::get);
                else {if(VoiceWorker.nativeLoaded)NativeVoice.release(0);VoiceModels.importZip(context,in,cancelled::get);}
                success=true;
            }catch(Exception ignored){}
            final boolean installed=success;
            runOnUiThread(()->{
                if(cancelled.get()||isFinishing())return;
                busy=false;button.setEnabled(true);
                if(voiceKey) {
                    updateEngines();
                    voiceKeyStatus.setText(installed?"VoiceKey 模型已校验安装，可选择该引擎。":"VoiceKey 导入失败：必须选择固定版本的完整官方语音包，并预留足够空间；旧模型保持不变。");
                } else status.setText(installed?"模型已校验安装 · 可返回键盘使用":"导入未完成：请选择配套完整模型 ZIP，并检查剩余空间。校验失败不会覆盖已有模型。");
            });
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==2)status.setText(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED?"麦克风已允许 · 只在显式开始录音后使用":"未允许麦克风 · 仍可使用拼音键盘");}
    private void showLicenses(){
        try{String[] files=getAssets().list("voice-licenses");new android.app.AlertDialog.Builder(this).setTitle("语音组件许可").setItems(files,(dialog,index)->{
            VoiceWorker.EXECUTOR.execute(()->{String text;try(InputStream in=getAssets().open("voice-licenses/"+files[index])){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException();bytes.write(buffer,0,n);}text=new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){text="完整许可随安装包提供。";}final String content=text;runOnUiThread(()->{if(cancelled.get()||isFinishing())return;TextView view=Ui.text(this,content,12,Ui.INK);ScrollView scroll=new ScrollView(this);scroll.addView(view);new android.app.AlertDialog.Builder(this).setTitle(files[index]).setView(scroll).setPositiveButton("关闭",null).show();});});
        }).show();}catch(Exception e){status.setText("许可随安装包与模型包提供。");}
    }
    @Override public void onDestroy(){cancelled.set(true);super.onDestroy();}
}
