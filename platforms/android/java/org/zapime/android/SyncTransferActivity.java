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
import java.io.IOException;

/** Manual transfer with explicit consent for the displayed server and root. */
public final class SyncTransferActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView localStatus,result;
    private Button synchronize,cancel;
    private SyncKeyRegistration.Selection selection;
    private SyncHttp.Cancellation cancellation;
    private AlertDialog confirmation;
    private boolean active,busy,refreshOnResume;
    private int generation;
    private interface Work {Outcome run(SyncHttp.Cancellation cancellation)throws Exception;}
    private static final class Outcome {
        final SyncKeyRegistration.Selection selection;
        final SyncRound.Result result;
        Outcome(SyncKeyRegistration.Selection selection,SyncRound.Result result){this.selection=selection;this.result=result;}
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page=Ui.page(this);page.addView(Ui.text(this,"手动同步个人资料",28,Ui.INK));
        page.addView(Ui.text(this,"默认只在本机保存。打开此页不会联网；每次明确确认后，才执行一次有上限的加密同步。",14,Ui.MUTED));
        LinearLayout local=Ui.card(page);local.addView(Ui.text(this,"本次连接与密钥",20,Ui.INK));localStatus=Ui.text(this,"正在读取本机状态…",14,Ui.INK);localStatus.setSaveEnabled(false);local.addView(localStatus);
        LinearLayout actions=Ui.card(page);result=Ui.text(this,"尚未开始。请先在独立页面核对或首次登记服务器密钥身份。",14,Ui.MUTED);result.setSaveEnabled(false);actions.addView(result);
        synchronize=Ui.button(this,"确认一次手动同步",Ui.BLUE,true);Ui.addButton(actions,synchronize);synchronize.setOnClickListener(v->confirm());
        cancel=Ui.button(this,"请求停止本轮",Ui.BG,false);Ui.addButton(actions,cancel);cancel.setOnClickListener(v->{if(cancellation!=null){cancellation.cancel();cancel.setEnabled(false);result.setText("已请求停止，正在等待当前操作返回。已经提交的密文或本机事务可能完成；下次会核对原请求，不会自动重试。");}});
        page.addView(Ui.text(this,"同步范围：词库、候选规则、快捷短语、永久收藏和你保存的剪贴板，包括待同步的修改及删除。正文在本机加密；服务器仍可见类型、修订与设备等元数据。语音模型、录音、恢复码和根密钥不上传。\n\n本轮最多先读取2页、上传16个已冻结操作，再读取2页（每页最多100条）。没有后台轮询；达到上限可稍后再次手动继续。网络取消是协作式，阻塞IO可能稍后返回。\n\n发现不同密钥、资料冲突、无法解密或不一致确认时立即停止，保留未同步资料与冻结请求。冲突解决、旧历史迁移和完整新设备配对仍在开发。试用前请分别保留个人资料备份、密钥恢复文件与独立恢复码。",12,Ui.MUTED));
        Ui.showPage(this,page);availability();
    }
    @Override public void onStart(){super.onStart();active=true;refreshOnResume=false;load();}
    @Override public void onResume(){super.onResume();if(refreshOnResume){active=true;refreshOnResume=false;load();}}
    @Override public void onPause(){invalidate();refreshOnResume=true;super.onPause();}
    @Override public void onStop(){invalidate();super.onStop();}
    private void invalidate(){active=false;++generation;if(cancellation!=null)cancellation.cancel();cancellation=null;if(confirmation!=null){confirmation.dismiss();confirmation=null;}selection=null;busy=false;availability();}
    private void availability(){if(synchronize!=null){synchronize.setEnabled(active&&!busy&&selection!=null);cancel.setEnabled(active&&busy&&cancellation!=null);}}
    private void load(){
        selection=null;busy=false;availability();localStatus.setText("正在读取本机状态…");
        run("只读取本机状态；此次不联网…",request->{
            try(SyncConnection connection=SyncConnectionStore.load(getApplicationContext())){
                if(connection==null)throw new IOException("sync_connection_missing");
                SyncKeyRegistration.Descriptor local=SyncKeyRegistration.preparedLocal(getApplicationContext(),connection,request);
                return new Outcome(new SyncKeyRegistration.Selection(connection.account,connection.device,connection.server,local),null);
            }
        });
    }
    private void run(String message,Work work){
        if(!active||busy)return;final SyncHttp.Cancellation request=new SyncHttp.Cancellation();cancellation=request;final int ticket=++generation;busy=true;availability();result.setText(message);
        try{SyncWorker.EXECUTOR.execute(()->{
            Outcome outcome=null;Exception failure=null;
            try{request.check();outcome=work.run(request);request.check();}catch(Exception error){failure=error;}catch(LinkageError unavailable){failure=new IOException("sync_transfer_unavailable");}
            final Outcome done=outcome;final Exception error=failure;
            main.post(()->{
                if(!active||ticket!=generation)return;cancellation=null;busy=false;
                if(error!=null){
                    Throwable cause=error instanceof SyncRound.Failure?error.getCause():error;
                    if(cause!=null&&("sync_connection_changed".equals(cause.getMessage())||"sync_key_local_changed".equals(cause.getMessage()))){selection=null;localStatus.setText("连接或密钥已变化。请返回重开本页，读取并确认新的本机状态。");}
                    result.setText(message(error));
                }
                else if(done!=null&&done.selection!=null){selection=done.selection;localStatus.setText("服务器："+selection.server+"\n账号："+selection.account+"\n设备："+selection.device+"\n密钥编号："+selection.local.keyId+"\n材料摘要（SHA256）：\n"+selection.local.materialSha256);result.setText("本机材料已核对。尚未联网；确认后才开始本轮传输。");}
                else if(done!=null&&done.result!=null)result.setText(describe(done.result));
                availability();
            });
        });}catch(java.util.concurrent.RejectedExecutionException full){request.cancel();cancellation=null;busy=false;availability();result.setText("同步后台任务仍在处理，请稍后重试。普通输入不受影响。");}
    }
    private void confirm(){
        if(!active||busy||selection==null)return;final SyncKeyRegistration.Selection selected=selection;final int ticket=generation;
        confirmation=new AlertDialog.Builder(this).setTitle("向这台服务器同步资料？")
                .setMessage("服务器："+selected.server+"\n账号："+selected.account+"\n密钥编号："+selected.local.keyId+"\n设备："+selected.device+"\n材料摘要（SHA256）：\n"+selected.local.materialSha256+"\n\n将核对服务器密钥身份，下载并认证远端资料，并上传本机待同步的词库、候选规则、短语、收藏和已保存剪贴板的密文，包括删除记录。只执行本轮，不开启后台同步。\n\n如果连接或本机密钥发生变化，本轮会停止；冲突不自动覆盖。")
                .setNegativeButton("取消",null).setPositiveButton("同步本轮",(dialog,which)->{confirmation=null;if(!active||busy||ticket!=generation||selected!=selection)return;run("正在核对密钥并执行本轮加密同步…",request->new Outcome(null,SyncTransfer.run(getApplicationContext(),selected,request)));}).create();
        confirmation.setOnDismissListener(dialog->confirmation=null);confirmation.show();confirmation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private static String describe(SyncRound.Result done){
        String text="本轮已确认上传 "+done.uploaded+" 项，应用远端 "+done.applied+" 项，核对回声 "+done.echoed+" 项。\n已认证游标："+done.cursor+"；本轮所见服务器修订："+done.head+"。";
        if(done.downloadBudgetReached||done.uploadBudgetReached)text+="\n已达到本轮处理上限，可能仍有待处理资料。请稍后手动继续；不会自动联网。";
        else text+="\n本轮结束；这只反映本次所见状态，不代表其他离线设备已经同步。";
        return text+"\n同步后的词库与候选规则请重新进入输入框后使用；当前组字会话不会被强制刷新。";
    }
    private static String message(Exception failure){
        Throwable error=failure;String progress="";
        if(error instanceof SyncRound.Failure){SyncRound.Result done=((SyncRound.Failure)error).completed;progress="本轮停止前已确认上传 "+done.uploaded+" 项、应用远端 "+done.applied+" 项。\n";error=error.getCause();}
        if(error instanceof SyncTransfer.RejectedPage){String retained=((SyncTransfer.RejectedPage)error).retention;String detail="saved".equals(retained)?"远端页认证或应用失败，原密文页已单独保留。":"not_eligible".equals(retained)?"远端页被拒绝，且不符合失败页保存条件；未保存该页。":"远端页被拒绝，保存失败页也未成功。";return progress+detail+"本页游标未推进。本机资料和待同步操作保留。请先核对密钥、冲突与失败记录。";}
        if(error instanceof SyncHttp.Failure){switch(((SyncHttp.Failure)error).code){
            case "key_conflict":return progress+"服务器与本机密钥身份不同或尚未登记，传输已停止。请先在独立密钥页核对，不要覆盖本机材料。";
            case "revision_conflict":return progress+"服务器修订发生冲突，已停止；未覆盖本机资料，也未改写冻结请求。冲突解决流程仍在开发。";
            case "unauthorized":case "forbidden":return progress+"设备凭据无效、到期、已撤销或权限不足。请重新核对设备授权。";
            case "storage_full":return progress+"服务器容量不足，本机待同步资料保留。";
            case "payload_too_large":return progress+"服务器拒绝过大的密文，本机原文和操作保留。";
            case "cancelled":return progress+"本轮已停止。已到达服务器的操作可能成功；原冻结请求保留，下次由原请求核对。";
            case "tls":return progress+"HTTPS证书或域名校验失败，本轮已停止。";
            case "redirect":return progress+"服务器返回跳转，凭据未转发。请核对连接地址。";
            case "limited":return progress+"服务器请求受限。请稍后手动继续，不会自动重试。";
            default:return progress+"网络或服务器响应异常，本轮已停止。未确认请求保留，下次手动重试仍使用原密文。";
        }}
        String code=error==null?"":error.getMessage();
        if("sync_connection_missing".equals(code))return "请先验证并保存设备连接，再准备或恢复本机密钥。";
        if("sync_connection_changed".equals(code)||"sync_key_local_changed".equals(code))return progress+"连接或密钥已变化，本轮停止。请返回重读本机状态后重新确认。";
        if("sync_key_requires_prepared".equals(code)||"sync_material_missing".equals(code))return "本机密钥尚未准备好或缺失。请恢复原材料，不要因为缺失而另建密钥。";
        if("sync_client_record_too_large".equals(code))return progress+"某条本机记录的完整编码超过同步大小上限，队列已暂停。原文与待同步操作保留，不会截断或跳过该条；请保留备份，等待该记录的单独处理。";
        if("sync_round_budget".equals(code))return progress+"已达到本轮时间预算。请稍后手动继续，未确认请求保留。";
        return progress+"无法安全完成本轮，本机资料和待同步操作保留。请核对备份、密钥与冲突状态后再继续。";
    }
}
