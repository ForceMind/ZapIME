// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.Arrays;

/** Manual device authorization. No personal-data access, scheduler or upload switch. */
public final class SyncActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private EditText server,credential;
    private TextView status;
    private Button connect,inspect,forget;
    private int generation;
    private boolean active,busy;
    private SyncHttp.Cancellation cancellation;
    private interface Work {String run(SyncHttp.Cancellation cancellation)throws Exception;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page=Ui.page(this);page.addView(Ui.text(this,"连接你的服务器",28,Ui.INK));
        page.addView(Ui.text(this,"验证设备授权，连接信息仅加密保存在本机。\n当前不会上传词库、短语、收藏或剪贴板。",14,Ui.MUTED));
        LinearLayout form=Ui.card(page);form.addView(Ui.text(this,"HTTPS 服务器地址",16,Ui.INK));
        server=new EditText(this);server.setHint("https://你的服务器域名");server.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);server.setSingleLine(true);Ui.field(server);form.addView(server);
        server.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|EditorInfo.IME_ACTION_NEXT);
        form.addView(Ui.text(this,"设备凭据",16,Ui.INK));credential=new EditText(this);credential.setHint("粘贴服务器 provision / recover-device 输出的凭据 JSON");
        credential.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        credential.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|EditorInfo.IME_ACTION_DONE);credential.setMinLines(3);Ui.field(credential);form.addView(credential);
        server.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2048)});credential.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)});
        for(EditText field:new EditText[]{server,credential}){field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);}
        connect=Ui.button(this,"验证并保存连接",Ui.BLUE,true);Ui.addButton(form,connect);connect.setOnClickListener(v->connect());
        LinearLayout saved=Ui.card(page);saved.addView(Ui.text(this,"本机连接",20,Ui.INK));status=Ui.text(this,"正在读取本机连接…",14,Ui.MUTED);saved.addView(status);
        inspect=Ui.button(this,"检查已保存连接",Ui.BG,false);Ui.addButton(saved,inspect);inspect.setOnClickListener(v->inspect());
        forget=Ui.button(this,"移除本机连接",Ui.BG,false);Ui.addButton(saved,forget);forget.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("移除本机连接？")
                .setMessage("之后需重新导入设备凭据。本机个人资料和端到端恢复材料保留；服务器端设备授权仍然有效。")
                .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->run("正在移除…",cancel->{cancel.check();SyncConnectionStore.forget(getApplicationContext());return "本机连接已移除。个人资料保留。";})).show());
        page.addView(Ui.text(this,"设备令牌只用于服务器授权，不能代替端到端解密密钥或恢复码。数据同步与新设备配对仍在开发。",12,Ui.MUTED));Ui.showPage(this,page);
    }
    @Override public void onStart(){super.onStart();active=true;run("正在读取本机连接…",cancel->{cancel.check();try(SyncConnection c=SyncConnectionStore.load(getApplicationContext())){return c==null?"尚未保存连接。输入服务器地址和设备凭据后验证。":"已保存连接（尚未检查当前授权状态）\n"+c.description();}});}
    @Override public void onStop(){active=false;++generation;if(cancellation!=null)cancellation.cancel();credential.setText("");super.onStop();}
    private void busy(boolean value){busy=value;for(Button b:new Button[]{connect,inspect,forget})b.setEnabled(!value);server.setEnabled(!value);credential.setEnabled(!value);}
    private void run(String message,Work work){
        if(!active)return;if(cancellation!=null)cancellation.cancel();final SyncHttp.Cancellation request=new SyncHttp.Cancellation();cancellation=request;final int ticket=++generation;
        busy(true);status.setText(message);
        SyncWorker.EXECUTOR.execute(()->{
            String result;
            try{request.check();result=work.run(request);}catch(Exception failure){result=message(failure);}
            final String visible=result;main.post(()->{if(!active||ticket!=generation)return;busy(false);status.setText(visible);});
        });
    }
    private void connect(){
        if(busy)return;final String origin=server.getText().toString(),json=credential.getText().toString();credential.setText("");
        run("正在通过 HTTPS 验证设备授权…",cancel->{
            try(SyncConnection imported=SyncConnection.imported(origin,json)){
                byte[] response=imported.inspect(cancel);
                try(SyncConnection verified=imported.verify(response)){
                    cancel.check();SyncConnectionStore.save(getApplicationContext(),verified);
                    return "授权验证通过，连接已加密保存。个人资料仍在本机。\n"+verified.description();
                }finally{Arrays.fill(response,(byte)0);}
            }
        });
    }
    private void inspect(){
        if(busy)return;run("正在检查当前设备授权…",cancel->{
            try(SyncConnection saved=SyncConnectionStore.load(getApplicationContext())){
                if(saved==null)return "尚未保存连接。";byte[] response=saved.inspect(cancel);
                try(SyncConnection verified=saved.verify(response)){return "当前设备授权有效。此次检查不上传个人资料。\n"+verified.description();}
                finally{Arrays.fill(response,(byte)0);}
            }
        });
    }
    private static String message(Exception error){
        if(error instanceof SyncHttp.Failure){switch(((SyncHttp.Failure)error).code){
            case "server":return "地址须为 HTTPS 域名或地址，可带端口；不含路径、账号、查询或片段。";
            case "credential":case "response":return "凭据或服务器响应格式不正确，原有连接保持不变。";
            case "identity":return "服务器返回的账号或设备与凭据不一致，连接未保存。";
            case "unauthorized":case "forbidden":return "设备凭据无效、已到期或已被撤销，请重新授权。";
            case "unsupported":return "服务器尚不支持设备身份验证，请升级 ZapIME 服务。";
            case "redirect":return "服务器返回了跳转，凭据未转发；请直接填写最终 HTTPS 地址。";
            case "tls":return "服务器证书或域名验证失败，请检查 HTTPS 配置。";
            case "limited":return "服务器请求受限，请稍后手动重试。";
            case "cancelled":return "本次操作已取消。";
            default:return "无法连接服务器，请检查网络后手动重试。";
        }}
        if("sync_connection_switch".equals(error.getMessage()))return "请先移除当前连接，再连接另一个账号或服务器。本机个人资料不会自动迁移。";
        if("sync_connection_profile".equals(error.getMessage()))return "本机资料库已绑定其他账号，连接未保存。移除连接不会改变资料库绑定；独立资料库迁移流程仍在开发。";
        if("sync_profile_read_failed".equals(error.getMessage()))return "无法核对本机资料库账号，连接未保存。请保留资料并检查数据库，禁止自动覆盖绑定。";
        return "无法读取或保存本机连接；现有资料不会自动上传。请保留原凭据后重试。";
    }
}
