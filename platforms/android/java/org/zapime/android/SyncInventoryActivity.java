// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.UUID;

/** Local metadata page only; opening this Activity never starts transfer. */
public final class SyncInventoryActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private TextView status;
    private Button refresh,next;
    private SyncKeyRegistration.Selection selection;
    private SyncHttp.Cancellation cancellation;
    private UUID nextAfter;
    private boolean active,busy,refreshOnResume;
    private int generation,pageNumber;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page=Ui.page(this);page.addView(Ui.text(this,"本地同步状态",28,Ui.INK));
        page.addView(Ui.text(this,"这里只读取本机记录的同步线索，不联网、不上传，也不展示资料正文。没有待发操作，不能证明资料已经备份到服务器。",14,Ui.MUTED));
        LinearLayout controls=Ui.card(page);status=Ui.text(this,"正在读取…",14,Ui.INK);status.setSaveEnabled(false);controls.addView(status);
        refresh=Ui.button(this,"从头刷新",Ui.BG,false);Ui.addButton(controls,refresh);refresh.setOnClickListener(v->load(null,1));
        next=Ui.button(this,"下一页",Ui.BLUE,true);Ui.addButton(controls,next);next.setOnClickListener(v->{if(nextAfter!=null)load(nextAfter,pageNumber+1);});
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);rows.setSaveEnabled(false);page.addView(rows);
        page.addView(Ui.text(this,"每页最多40条，包含删除标记，只保留当前页。各页来自独立读取；浏览期间若有编辑或新增，可从头刷新。\n\n“有服务端修订”只表示本机曾获知某个远端版本，不能保证当前内容已被确认或服务器仍保存它。“未找到同步记录”不代表远端不存在，更不是首次上传授权。备份恢复的存量资料首次发布仍在开发，请继续独立保存个人资料备份和密钥恢复文件。",12,Ui.MUTED));
        Ui.showPage(this,page);availability();
    }
    @Override public void onStart(){super.onStart();active=true;refreshOnResume=false;load(null,1);}
    @Override public void onResume(){super.onResume();if(refreshOnResume){active=true;refreshOnResume=false;load(null,1);}}
    @Override public void onPause(){invalidate();refreshOnResume=true;super.onPause();}
    @Override public void onStop(){invalidate();super.onStop();}
    private void invalidate(){active=false;++generation;if(cancellation!=null)cancellation.cancel();cancellation=null;selection=null;nextAfter=null;pageNumber=0;busy=false;if(rows!=null)rows.removeAllViews();if(status!=null)status.setText("已离开本地预览；返回后重新读取。");availability();}
    private void availability(){if(refresh!=null){refresh.setEnabled(active&&!busy);next.setEnabled(active&&!busy&&nextAfter!=null);}}
    private void load(UUID after,int number){
        if(!active||busy)return;final SyncKeyRegistration.Selection expected=after==null?null:selection;
        if(after!=null&&expected==null)return;
        final int ticket=++generation;final SyncHttp.Cancellation request=new SyncHttp.Cancellation();cancellation=request;busy=true;
        // A failed or cancelled read must not leave a previous page looking current.
        rows.removeAllViews();nextAfter=null;selection=null;status.setText("只读取本机状态；此次不联网…");availability();
        try{SyncWorker.EXECUTOR.execute(()->{
            SyncInventory.Result loaded=null;Exception failure=null;
            try{request.check();loaded=SyncInventory.load(getApplicationContext(),expected,after,request);request.check();}
            catch(Exception error){failure=error;}catch(LinkageError unavailable){failure=new IOException("sync_inventory_unavailable");}
            final SyncInventory.Result done=loaded;final Exception error=failure;
            main.post(()->{if(!active||ticket!=generation)return;cancellation=null;busy=false;
                if(error!=null||done==null){status.setText(message(error));}
                else{selection=done.selection;nextAfter=done.page.nextAfter;pageNumber=number;render(done.page);}
                availability();
            });
        });}catch(java.util.concurrent.RejectedExecutionException full){request.cancel();cancellation=null;busy=false;status.setText("同步后台任务仍在处理，请稍后重试。普通输入不受影响。");availability();}
    }
    private void render(SyncInventory.Page page){
        int pending=0,known=0,history=0,untracked=0;
        for(SyncInventory.Item item:page.items){
            if(item.state.equals("pending_local_operation"))pending++;else if(item.state.equals("server_version_known"))known++;else if(item.state.equals("local_operation_history"))history++;else untracked++;
            LinearLayout card=Ui.card(rows);TextView title=Ui.text(this,kind(item.kind)+(item.deleted?" · 已删除":"")+" · "+state(item.state),17,Ui.INK);title.setSaveEnabled(false);card.addView(title);
            String detail="记录："+item.id+"\n本地修订："+item.revision+"；本机已知服务修订："+(item.serverRevision==null?"无":item.serverRevision);
            if(item.pending)detail+="\n"+(item.currentPending?"当前完整版本有待发操作。":"存在待发操作，但不对应当前完整版本。");
            detail+="\n"+(item.currentHistory?"本机操作历史包含当前完整版本。":item.history?"本机操作历史未包含当前完整版本。":"未找到本机操作历史。");
            TextView body=Ui.text(this,detail,12,Ui.MUTED);body.setSaveEnabled(false);card.addView(body);
        }
        status.setText("账号："+selection.account+"\n第 "+pageNumber+" 页 · 本页 "+page.items.size()+" 条\n存在待发操作 "+pending+" · 有服务端修订 "+known+"\n仅本机操作历史 "+history+" · 未找到同步记录 "+untracked+(page.items.isEmpty()?"\n本页没有记录。":"")+"\n以上为本页互斥分类，非全库数量或云备份完成证明。"+(nextAfter==null?" 当前已到本次浏览末页。":""));
    }
    private static String kind(int value){return new String[]{"","词库","候选规则","快捷短语","永久收藏","剪贴板"}[value];}
    private static String state(String value){switch(value){case "pending_local_operation":return "存在待发操作";case "server_version_known":return "有服务端修订";case "local_operation_history":return "仅本机操作历史";default:return "未找到同步记录";}}
    private static String message(Exception failure){
        String code=failure==null?"":failure.getMessage();
        if("sync_connection_missing".equals(code))return "请先保存服务器与设备连接，并完成本机密钥准备，再查看本地状态。";
        if("sync_connection_changed".equals(code)||"sync_key_local_changed".equals(code))return "连接或密钥已变化，本次结果已丢弃。请从头刷新。";
        if("sync_key_requires_prepared".equals(code)||"sync_material_missing".equals(code))return "本机密钥或资料库尚未准备好，请到准备页面处理；本页不会创建、换绑或恢复材料。";
        if("sync_cancelled".equals(code))return "本次读取已取消。";
        return "未能完成本地读取，可能是资料库忙、查询达到预算或本机绑定不可用。没有上传或改动资料；可稍后从头刷新。";
    }
}
