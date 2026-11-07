// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;

/** Public account-key identity only. Opening/resuming this page never uses HTTP.
 * The preparation page retains its local-only contract; neither page authorizes
 * personal-data upload or claims successful device pairing.
 */
public final class SyncKeyRegistrationActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView localStatus,remoteStatus;
    private Button inspect,register;
    private UUID expectedAccount;
    private SyncKeyRegistration.Selection selection;
    private boolean active,busy,prepared,owner,refreshOnResume;
    private int generation;
    private SyncHttp.Cancellation cancellation;
    private AlertDialog confirmation;
    private SyncKeyRegistration.Result remote;
    private interface Work {Outcome run(SyncHttp.Cancellation cancellation)throws Exception;}
    private static final class Outcome {
        final Local local;
        final SyncKeyRegistration.Result remote;
        Outcome(Local local,SyncKeyRegistration.Result remote){this.local=local;this.remote=remote;}
    }
    private static final class Local {
        final UUID account,device;
        final URI server;
        final String role;
        final boolean prepared;
        final SyncKeyRegistration.Descriptor descriptor;
        Local(UUID account,UUID device,URI server,String role,boolean prepared,SyncKeyRegistration.Descriptor descriptor){
            this.account=account;this.device=device;this.server=server;this.role=role;this.prepared=prepared;this.descriptor=descriptor;
        }
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page=Ui.page(this);page.addView(Ui.text(this,"核对服务器密钥",28,Ui.INK));
        page.addView(Ui.text(this,"确认本机与服务器记录的是同一套加密密钥。打开此页只读取本机状态，按下方按钮才会联网。",14,Ui.MUTED));
        LinearLayout local=Ui.card(page);local.addView(Ui.text(this,"本机密钥身份",20,Ui.INK));
        localStatus=Ui.text(this,"正在读取本机状态…",14,Ui.INK);localStatus.setSaveEnabled(false);local.addView(localStatus);
        LinearLayout server=Ui.card(page);server.addView(Ui.text(this,"服务器记录",20,Ui.INK));
        remoteStatus=Ui.text(this,"尚未联网核对。本页不会上传个人资料。",14,Ui.MUTED);remoteStatus.setSaveEnabled(false);server.addView(remoteStatus);
        inspect=Ui.button(this,"核对服务器密钥",Ui.BLUE,true);Ui.addButton(server,inspect);inspect.setOnClickListener(v->inspect());
        register=Ui.button(this,"首次登记此密钥",Ui.BG,false);Ui.addButton(server,register);register.setOnClickListener(v->confirmRegistration());
        page.addView(Ui.text(this,"首次登记只发送公开的密钥编号和材料摘要，不发送根密钥、恢复码或恢复文件，也不授权资料上传。\n\n已有其他设备时，请先导入同一套恢复材料，再核对。发现不同密钥或旧同步历史时，本页会停止；不会换根、覆盖资料或修改待同步操作。\n\n密钥身份相符不代表设备已配对或资料已同步。资料同步须另行明确确认；设备配对及历史迁移仍在开发。",12,Ui.MUTED));
        Ui.showPage(this,page);availability();
    }
    @Override public void onStart(){super.onStart();active=true;refreshOnResume=false;loadLocal();}
    @Override public void onResume(){super.onResume();if(refreshOnResume){active=true;refreshOnResume=false;loadLocal();}}
    @Override public void onPause(){invalidate();refreshOnResume=true;super.onPause();}
    @Override public void onStop(){invalidate();super.onStop();}
    private void invalidate(){
        active=false;++generation;if(cancellation!=null)cancellation.cancel();cancellation=null;
        if(confirmation!=null){confirmation.dismiss();confirmation=null;}
        expectedAccount=null;selection=null;prepared=false;owner=false;remote=null;busy=false;availability();
        if(remoteStatus!=null)remoteStatus.setText("尚未联网核对。返回后请重新手动核对服务器状态。");
    }
    private void availability(){
        if(inspect==null)return;boolean ready=active&&!busy&&prepared&&selection!=null&&expectedAccount!=null;
        inspect.setEnabled(ready);register.setEnabled(ready&&owner&&(remote==null||"empty".equals(remote.state)));
    }
    private void run(String message,Work work){
        if(!active||busy)return;
        if(cancellation!=null)cancellation.cancel();final SyncHttp.Cancellation request=new SyncHttp.Cancellation();cancellation=request;
        final int ticket=++generation;busy=true;availability();remoteStatus.setText(message);
        try{SyncWorker.EXECUTOR.execute(()->{
            Outcome outcome=null;String failure=null;
            try{request.check();outcome=work.run(request);request.check();}
            catch(Exception error){failure=message(error);}
            catch(LinkageError unavailable){failure="当前安装版本无法读取密钥身份，请保留材料并升级完整安装包。";}
            final Outcome result=outcome;final String error=failure;
            main.post(()->{
                if(!active||ticket!=generation)return;cancellation=null;busy=false;
                if(error!=null){remote=null;remoteStatus.setText(error);}
                else if(result!=null){
                    if(result.local!=null)showLocal(result.local);
                    remote=result.remote;
                    remoteStatus.setText(remote==null?"尚未联网核对。请手动核对服务器密钥。":describe(remote));
                }
                availability();
            });
        });}catch(java.util.concurrent.RejectedExecutionException full){
            request.cancel();cancellation=null;busy=false;remote=null;availability();remoteStatus.setText("同步后台任务仍在处理，请稍后重开本页或重试。普通输入不受影响。");
        }
    }
    private void loadLocal(){
        expectedAccount=null;selection=null;prepared=false;owner=false;remote=null;busy=false;availability();
        localStatus.setText("正在读取本机状态…");
        run("正在读取本机状态；此次不联网…",cancel->new Outcome(readLocal(cancel),null));
    }
    private Local readLocal(SyncHttp.Cancellation cancellation)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("sync_key_requires_worker");
        cancellation.check();
        try(SyncConnection connection=SyncConnectionStore.load(getApplicationContext())){
            if(connection==null)throw new IOException("sync_connection_missing");
            SyncPreparation.State state=SyncPreparation.inspect(getApplicationContext());
            if(!connection.account.equals(state.connectedAccount))throw new IOException("sync_connection_changed");
            SyncKeyRegistration.Descriptor descriptor=null;
            if(state.prepared){
                byte[] material=SyncSecrets.load(getApplicationContext(),connection.account),publicWire=null;
                try{
                    if(material==null)throw new IOException("sync_material_missing");
                    String raw=NativeCore.syncKeyDescriptor(connection.account.toString(),material);
                    if(raw==null||raw.length()>1024)throw new IOException("sync_key_local_unavailable");
                    publicWire=raw.getBytes(StandardCharsets.UTF_8);descriptor=SyncKeyRegistration.parseDescriptor(publicWire);
                    String root=new File(getFilesDir(),"zapime").getAbsolutePath();
                    raw=NativeCore.syncWorkspaceStatus(root,connection.account.toString(),material);
                    if(raw==null||raw.length()>512)throw new IOException("sync_key_local_changed");
                    JSONObject checked=new JSONObject(raw);
                    if(checked.length()!=5||!Boolean.TRUE.equals(checked.opt("initialized"))||!Boolean.TRUE.equals(checked.opt("material_present"))||!Boolean.TRUE.equals(checked.opt("prepared"))
                            ||!connection.account.toString().equals(checked.opt("account_id"))||!descriptor.keyId.toString().equals(checked.opt("key_id"))||!descriptor.keyId.equals(state.keyId))throw new IOException("sync_key_local_changed");
                }finally{if(material!=null)Arrays.fill(material,(byte)0);if(publicWire!=null)Arrays.fill(publicWire,(byte)0);}
            }
            cancellation.check();
            try(SyncConnection current=SyncConnectionStore.load(getApplicationContext())){
                if(current==null||!current.matchesSnapshot(connection))throw new IOException("sync_connection_changed");
            }
            return new Local(connection.account,connection.device,connection.server,connection.role,state.prepared,descriptor);
        }
    }
    private void showLocal(Local local){
        expectedAccount=local.account;prepared=local.prepared;owner="owner".equals(local.role);
        selection=local.prepared&&local.descriptor!=null?new SyncKeyRegistration.Selection(local.account,local.device,local.server,local.descriptor):null;
        String text="服务器："+local.server+"\n账号："+local.account+"\n设备角色："+(owner?"管理员设备":"成员设备");
        if(local.prepared&&local.descriptor!=null)text+="\n\n密钥编号："+local.descriptor.keyId+"\n材料摘要（SHA256）：\n"+local.descriptor.materialSha256;
        else text+="\n\n本机密钥尚未准备好。请返回上一页，先完成本机密钥准备或恢复。";
        localStatus.setText(text);
    }
    private void inspect(){
        if(!active||busy||!prepared||selection==null)return;final SyncKeyRegistration.Selection selected=selection;
        run("正在通过 HTTPS 核对公开密钥身份…",cancel->new Outcome(null,SyncKeyRegistration.inspect(getApplicationContext(),selected,cancel)));
    }
    private void confirmRegistration(){
        if(!active||busy||!prepared||!owner||selection==null)return;final SyncKeyRegistration.Selection selected=selection;
        final int ticket=generation;
        confirmation=new AlertDialog.Builder(this).setTitle("首次登记这套密钥？")
                .setMessage("服务器："+selected.server+"\n将登记账号："+selected.account+"\n密钥编号："+selected.local.keyId+"\n材料摘要（SHA256）：\n"+selected.local.materialSha256+"\n\n仅管理员设备可为没有同步历史的空账号首次登记。将通过 HTTPS 发送上述公开身份，不发送根密钥、恢复码、恢复文件或个人资料。\n\n如果其他设备已有密钥，请取消，先恢复同一套材料。服务器若已有不同密钥或旧历史，将拒绝；本机材料和资料保留。\n\n此操作不授权上传资料，也不完成设备配对。")
                .setNegativeButton("取消",null).setPositiveButton("明确登记",(dialog,which)->{
                    confirmation=null;
                    if(!active||busy||ticket!=generation||selection!=selected)return;
                    run("正在核对服务器并尝试首次登记…",cancel->new Outcome(null,SyncKeyRegistration.register(getApplicationContext(),selected,cancel)));
                }).create();
        confirmation.setOnDismissListener(dialog->confirmation=null);confirmation.show();
        confirmation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private static String describe(SyncKeyRegistration.Result result){
        if("empty".equals(result.state))return "服务器尚未登记密钥，且没有同步历史。这个结果不证明其他设备没有密钥；请先确认已有设备和恢复材料。\n\n管理员可明确首次登记，成员需由管理员处理。本机资料仍在本机。";
        if("legacy".equals(result.state))return "服务器存在旧同步历史，但没有密钥身份记录。已停止登记，请保留本机资料和恢复材料，等待明确的历史迁移流程。";
        String identity="服务器密钥编号："+result.keyId+"\n材料摘要（SHA256）：\n"+result.materialSha256+"\n登记设备："+result.registeredBy+"\n登记时间："+result.registeredAt;
        if(result.matchesLocal)return "本机与服务器登记的密钥身份相符。此次只完成身份核对，没有上传个人资料，也不代表已配对或已同步。\n\n"+identity;
        return "服务器登记的密钥与本机不同，已停止。请保留本机资料及恢复材料，不要另建密钥或覆盖待同步操作。需要先核对原设备并通过明确的恢复或迁移流程处理。\n\n"+identity;
    }
    private static String message(Exception error){
        if(error instanceof SyncHttp.Failure){switch(((SyncHttp.Failure)error).code){
            case "key_conflict":return "服务器已有不同密钥或旧历史，登记已停止。本机密钥、资料和待同步操作保持原样；请保留恢复材料并核对原设备。";
            case "key_response":case "response":return "服务器密钥身份响应不符合约定，本次结果未被接受。请保留材料并检查服务器版本。";
            case "unauthorized":return "设备凭据无效、已到期或已被撤销，请返回连接页重新授权。";
            case "forbidden":return "当前设备没有首次登记权限。成员可以核对，首次登记需由当前有效管理员完成。";
            case "unsupported":return "服务器尚不支持密钥身份登记，请先升级 ZapIME 同步服务。";
            case "redirect":return "服务器返回了跳转，凭据未转发。请在连接页填写最终 HTTPS 地址。";
            case "tls":return "服务器证书或域名验证失败，请检查 HTTPS 配置。";
            case "limited":return "服务器请求受限，请稍后手动重试。";
            case "cancelled":return "本次操作已取消。若登记已到达服务器，返回后请手动重新核对；不会自动重试。";
            default:return "暂时无法连接服务器。请检查网络后手动核对，不会自动重试。";
        }}
        String code=error.getMessage();
        if("sync_connection_missing".equals(code))return "尚未保存设备连接。请返回连接页验证并保存凭据。";
        if("sync_connection_changed".equals(code))return "本机连接已变化，本次结果未被接受。返回后请重新核对。";
        if("sync_key_requires_prepared".equals(code)||"sync_material_missing".equals(code))return "请先完成本机密钥准备或恢复，再核对服务器身份。不要因材料缺失而另建密钥。";
        if("sync_key_local_changed".equals(code))return "本机材料或资料库身份已变化，本次结果未被接受。请保留原资料和恢复材料后核对。";
        return "无法安全读取本机密钥身份。请保留资料、密钥恢复文件和独立恢复码，检查本机准备状态后重试。";
    }
}
