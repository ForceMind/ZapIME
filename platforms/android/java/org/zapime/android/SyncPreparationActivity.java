// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit device-local key preparation. No network, upload toggle or account pairing. */
public final class SyncPreparationActivity extends Activity {
    private static final int SAVE_RECOVERY=201,OPEN_RECOVERY=202,MAX_RECOVERY=16*1024;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView status,recovery;
    private EditText confirmCode,restoreCode;
    private Button create,export,choose,restore,confirm,reload;
    private UUID currentAccount,pickerAccount,restoreAccount,pendingAccount;
    private Uri pendingUri,restoreUri;
    private int pendingOperation,generation;
    private boolean active,busy,initialized,prepared;
    private byte[] expectedCode;
    private AtomicBoolean cancelled=new AtomicBoolean();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page=Ui.page(this);
        page.addView(Ui.text(this,"准备加密同步",27,Ui.INK));
        page.addView(Ui.text(this,"先在本机准备密钥，再保存密钥恢复文件。此页面不会连接服务器，也不会上传个人资料。",14,Ui.MUTED));
        status=Ui.text(this,"正在读取本机状态…",14,Ui.BLUE);page.addView(status);
        reload=Ui.button(this,"重新读取本机状态",Ui.BG,false);Ui.addButton(page,reload);reload.setOnClickListener(v->{if(active&&!busy)refresh();});
        LinearLayout setup=Ui.card(page);setup.addView(Ui.text(this,"首次准备",20,Ui.INK));
        setup.addView(Ui.text(this,"如果这个账号已有其他设备，请导入同一份密钥恢复文件，不要创建另一套密钥。",13,Ui.MUTED));
        create=Ui.button(this,"为首次使用准备密钥…",Ui.BLUE,true);Ui.addButton(setup,create);
        create.setOnClickListener(v->confirmCreate());
        LinearLayout save=Ui.card(page);save.addView(Ui.text(this,"保存密钥恢复文件",20,Ui.INK));
        save.addView(Ui.text(this,"文件只保存同步密钥和所属账号，不含词库、收藏、设备凭据或语音模型。它与本机资料备份用途不同，不能单独恢复全部资料。",13,Ui.MUTED));
        export=Ui.button(this,"选择位置并导出密钥恢复文件",Ui.BLUE,true);Ui.addButton(save,export);
        export.setOnClickListener(v->{if(canUse()&&prepared)picker(Intent.ACTION_CREATE_DOCUMENT,SAVE_RECOVERY,"ZapIME-sync-key-"+System.currentTimeMillis()+".zapime-key");});
        save.addView(Ui.text(this,"文件写入并读回核对后才显示恢复码。请抄写，并与文件分开保管。切换应用或离开页面会清除本页恢复码；可以重新导出。",13,Ui.MUTED));
        recovery=Ui.text(this,"",17,Ui.INK);recovery.setTypeface(android.graphics.Typeface.MONOSPACE);
        recovery.setSaveEnabled(false);recovery.setLongClickable(false);save.addView(recovery);
        confirmCode=secret("抄写后输入43个字符核对");save.addView(confirmCode);
        confirm=Ui.button(this,"核对恢复码",Ui.BG,false);Ui.addButton(save,confirm);
        confirm.setOnClickListener(v->confirmRecoveryCode());
        LinearLayout load=Ui.card(page);load.addView(Ui.text(this,"已有密钥恢复文件",20,Ui.INK));
        choose=Ui.button(this,"选择密钥恢复文件",Ui.BG,false);Ui.addButton(load,choose);
        choose.setOnClickListener(v->{if(canUse())picker(Intent.ACTION_OPEN_DOCUMENT,OPEN_RECOVERY,null);});
        restoreCode=secret("输入该文件对应的43字符恢复码");load.addView(restoreCode);
        restore=Ui.button(this,"验证并导入密钥…",Ui.BLUE,true);Ui.addButton(load,restore);
        restore.setOnClickListener(v->confirmRestore());
        page.addView(Ui.text(this,"本机准备完成不表示已同步或已向服务器登记密钥。资料上传、新设备配对和完整账号恢复仍需后续接入。移除连接也不会清除这里的密钥或资料库绑定。",12,Ui.MUTED));
        Ui.showPage(this,page);controls();
    }

    private EditText secret(String hint){
        EditText field=new EditText(this);field.setHint(hint);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(43)});Ui.field(field);return field;
    }
    private static byte[] code(EditText input)throws IOException {
        if(input.length()!=43)throw new IOException("sync_recovery_invalid");
        byte[] bytes=new byte[43];
        for(int i=0;i<43;i++){
            char c=input.getText().charAt(i);
            if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-')){
                Arrays.fill(bytes,(byte)0);throw new IOException("sync_recovery_invalid");
            }
            bytes[i]=(byte)c;
        }
        return bytes;
    }
    private boolean canUse(){return active&&!busy&&initialized&&currentAccount!=null;}
    private void controls(){
        if(create==null)return;
        reload.setEnabled(active&&!busy);
        boolean ready=canUse();create.setEnabled(ready&&!prepared);export.setEnabled(ready&&prepared);
        choose.setEnabled(ready);restore.setEnabled(ready&&restoreUri!=null&&currentAccount.equals(restoreAccount));
        confirm.setEnabled(active&&!busy&&expectedCode!=null);confirmCode.setEnabled(!busy);restoreCode.setEnabled(!busy);
    }
    private void setBusy(boolean value){busy=value;controls();}
    private int begin(String message){
        cancelled.set(true);cancelled=new AtomicBoolean();int ticket=++generation;clearSecrets();setBusy(true);status.setText(message);return ticket;
    }
    private boolean current(int ticket){return active&&ticket==generation;}
    private void render(SyncPreparation.State state,String message){
        UUID previous=currentAccount;currentAccount=state.connectedAccount;initialized=state.initialized;prepared=state.prepared;
        if(previous!=null&&!previous.equals(currentAccount)){restoreUri=null;restoreAccount=null;}
        String text;
        if(!state.initialized)text="本机资料库尚未初始化。请返回个人资料页打开资料库后，再来准备。";
        else if(state.prepared)text="本机同步材料已准备，资料未上传。\n请保存密钥恢复文件和对应恢复码。";
        else if(state.materialPresent)text="密钥已保存在本机，资料库准备尚未完成。请继续完成准备；不会重新生成密钥。";
        else text="尚未准备本机同步材料。首次使用可创建；已有其他设备请导入同一份恢复文件。";
        create.setText(state.materialPresent?"继续完成本机准备":"为首次使用准备密钥…");
        status.setText((message==null?text:message+"\n"+text)+"\n账号："+state.connectedAccount);
        controls();
    }
    private void refresh(){
        final int ticket=begin("正在读取本机状态…");final AtomicBoolean stop=cancelled;
        try{SyncWorker.EXECUTOR.execute(()->{
            try{BackupFiles.check(stop::get);final SyncPreparation.State state=SyncPreparation.inspect(getApplicationContext());BackupFiles.check(stop::get);
                main.post(()->{if(!current(ticket))return;setBusy(false);render(state,null);consumePending();});
            }catch(Exception|LinkageError failure){fail(ticket,failure,true);}
        });}catch(RejectedExecutionException failure){fail(ticket,failure,true);}
    }
    private void confirmCreate(){
        if(!canUse()||prepared)return;final UUID account=currentAccount;
        new AlertDialog.Builder(this).setTitle("准备首次使用的同步密钥？")
                .setMessage("仅在首次为这个账号设置加密资料时创建。已有其他设备或恢复文件，请改用导入，避免产生另一套密钥。此处无法判断其他设备的密钥状态。准备仅在本机完成，不会上传资料或向服务器登记。")
                .setNegativeButton("取消",null).setPositiveButton("准备本机密钥",(d,w)->create(account)).show();
    }
    private void create(UUID account){
        if(!canUse()||!account.equals(currentAccount))return;
        final int ticket=begin("正在准备本机同步材料…");final AtomicBoolean stop=cancelled;
        try{SyncWorker.EXECUTOR.execute(()->{
            try{BackupFiles.check(stop::get);final SyncPreparation.State state=SyncPreparation.create(getApplicationContext(),account);BackupFiles.check(stop::get);
                main.post(()->{if(!current(ticket))return;setBusy(false);render(state,"本次本机准备完成。");});
            }catch(Exception|LinkageError failure){fail(ticket,failure,false);}
        });}catch(RejectedExecutionException failure){fail(ticket,failure,false);}
    }
    private void picker(String action,int request,String filename){
        clearSecrets();pickerAccount=currentAccount;
        Intent intent=new Intent(action).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(request==SAVE_RECOVERY)intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if(filename!=null)intent.putExtra(Intent.EXTRA_TITLE,filename);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
        try{startActivityForResult(intent,request);}catch(RuntimeException unavailable){pickerAccount=null;status.setText("无法打开系统文件选择器，请稍后重试。");}
    }
    @Override protected void onActivityResult(int operation,int result,Intent intent){
        super.onActivityResult(operation,result,intent);
        if(operation!=SAVE_RECOVERY&&operation!=OPEN_RECOVERY)return;
        UUID account=pickerAccount;pickerAccount=null;
        if(result!=RESULT_OK||intent==null||intent.getData()==null||account==null)return;
        pendingOperation=operation;pendingUri=intent.getData();pendingAccount=account;
        if(active&&!busy)consumePending();
    }
    private void consumePending(){
        if(pendingUri==null||!active||busy)return;
        Uri uri=pendingUri;UUID account=pendingAccount;int operation=pendingOperation;pendingUri=null;pendingAccount=null;
        if(!canUse()||!currentAccount.equals(account)){status.setText("账号或本机状态已变化，请重新选择文件。");return;}
        if(operation==SAVE_RECOVERY){if(prepared)export(uri,account);else status.setText("请先完成本机材料准备，再导出恢复文件。");}
        else {restoreUri=uri;restoreAccount=account;status.setText("已选择密钥恢复文件。请输入对应恢复码，再显式验证并导入。");controls();}
    }
    private void export(Uri uri,UUID account){
        final int ticket=begin("正在创建并写后核对密钥恢复文件…");final AtomicBoolean stop=cancelled;
        try{SyncWorker.EXECUTOR.execute(()->{
            byte[] code=new byte[43],encrypted=null;boolean handed=false;
            try{BackupFiles.check(stop::get);encrypted=SyncPreparation.recovery(getApplicationContext(),account,code);BackupFiles.check(stop::get);
                BackupFiles.writeVerified(new BackupFiles.Document(){public OutputStream write()throws IOException{return getContentResolver().openOutputStream(uri,"wt");}public InputStream read()throws IOException{return getContentResolver().openInputStream(uri);}},encrypted,stop::get,MAX_RECOVERY);
                BackupFiles.check(stop::get);final byte[] shown=code;
                handed=main.post(()->{if(!current(ticket)){Arrays.fill(shown,(byte)0);return;}expectedCode=shown;recovery.setText(new String(shown,StandardCharsets.US_ASCII));setBusy(false);status.setText("密钥恢复文件已写入并读回核对。请抄写恢复码，并与文件分开保存；可在本页输入核对。");});
            }catch(Exception|LinkageError failure){fail(ticket,failure,false);}
            finally{if(!handed)Arrays.fill(code,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
        });}catch(RejectedExecutionException failure){fail(ticket,failure,false);}
    }
    private void confirmRestore(){
        if(!canUse()||restoreUri==null||!currentAccount.equals(restoreAccount))return;
        final UUID account=currentAccount;final Uri uri=restoreUri;
        new AlertDialog.Builder(this).setTitle("导入已有同步密钥？")
                .setMessage("只接受属于当前账号且与已有资料库绑定一致的密钥。不会替换另一套密钥，不恢复个人资料或设备凭据，也不会上传资料。")
                .setNegativeButton("取消",null).setPositiveButton("验证并导入",(d,w)->restore(uri,account)).show();
    }
    private void restore(Uri uri,UUID account){
        if(!canUse()||!account.equals(currentAccount))return;
        final byte[] entered;
        try{entered=code(restoreCode);}catch(IOException invalid){status.setText("恢复码须为43个英文、数字、下划线或短横线字符。");return;}
        final int ticket=begin("正在认证密钥恢复文件…");final AtomicBoolean stop=cancelled;
        try{SyncWorker.EXECUTOR.execute(()->{
            byte[] encrypted=null;
            try{BackupFiles.check(stop::get);try(InputStream in=getContentResolver().openInputStream(uri)){encrypted=BackupFiles.read(in,stop::get,MAX_RECOVERY);}BackupFiles.check(stop::get);
                final SyncPreparation.State state=SyncPreparation.restore(getApplicationContext(),account,entered,encrypted);BackupFiles.check(stop::get);
                main.post(()->{if(!current(ticket))return;setBusy(false);render(state,"同步密钥已验证并保存在本机。");});
            }catch(Exception|LinkageError failure){fail(ticket,failure,false);}
            finally{Arrays.fill(entered,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
        });}catch(RejectedExecutionException failure){Arrays.fill(entered,(byte)0);fail(ticket,failure,false);}
    }
    private void confirmRecoveryCode(){
        if(!active||busy||expectedCode==null)return;byte[] entered=null;
        try{entered=code(confirmCode);if(!MessageDigest.isEqual(expectedCode,entered))throw new IOException();clearSecrets();controls();status.setText("恢复码核对一致。请将恢复码与密钥恢复文件分开妥善保存；资料尚未上传。");}
        catch(IOException mismatch){status.setText("恢复码不一致，请核对抄写的43个字符。");}
        finally{if(entered!=null)Arrays.fill(entered,(byte)0);}
    }
    private void fail(int ticket,Throwable failure,boolean reading){
        final String text=message(failure);
        main.post(()->{if(!current(ticket))return;if(reading){currentAccount=null;initialized=false;prepared=false;}setBusy(false);status.setText(text);});
    }
    private static String message(Throwable failure){
        String code=failure.getMessage();
        if("sync_connection_missing".equals(code))return "请先返回服务器连接页，验证并保存设备连接。准备材料本身不会联网。";
        if("sync_connection_changed".equals(code)||"sync_connection_profile".equals(code))return "连接账号已变化或与资料库不一致。请返回重新读取状态；现有密钥不会被替换。";
        if("sync_preparation_owner".equals(code))return "只有管理员设备可显式创建首次使用的密钥。此设备请导入同一账号已有的密钥恢复文件。";
        if("sync_workspace_uninitialized".equals(code))return "本机资料库尚未初始化。请返回个人资料页打开资料库后重试。";
        if("sync_material_missing".equals(code))return "缺少已有资料库所需的同步密钥。请导入原密钥恢复文件；不能创建新密钥覆盖。";
        if("sync_material_exists".equals(code))return "本机已有不同的同步密钥，未替换。请核对当前账号与原恢复文件。";
        if("sync_material_account_mismatch".equals(code)||"sync_workspace_binding_mismatch".equals(code))return "账号或密钥与已有资料库绑定不一致，未替换。请使用原账号和原密钥恢复文件。";
        if("sync_material_invalid".equals(code)||"sync_material_unreadable".equals(code)||"sync_material_format".equals(code)||"sync_material_size".equals(code)||"sync_key_unavailable".equals(code))return "已有同步保护材料不可读取，当前不会重置或覆盖。请保留原数据及恢复材料；可在全新安装或另一设备导入原密钥恢复包，个人资料仍需原资料或服务器备份。";
        if("sync_workspace_unavailable".equals(code)||"sync_profile_read_failed".equals(code))return "无法核对本机资料库，未更改账号绑定。请返回资料页检查，保留原资料后重试。";
        if("sync_recovery_invalid".equals(code)||"sync_material_recovery_invalid".equals(code))return "恢复码、文件用途或账号不匹配，或文件已损坏。未替换已有密钥。";
        if("backup_readback_failed".equals(code))return "恢复文件写后核对失败，未完成导出。请换位置重新导出。";
        if("backup_file_too_large".equals(code)||"backup_file_empty".equals(code))return "密钥恢复文件为空或超过16 KiB，未导入。请选择正确的密钥恢复文件。";
        if("backup_cancelled".equals(code))return "操作已停止。已经开始的本机保存可能完成，请重新进入查看真实状态。";
        return "本机准备未完成。请保留原密钥与恢复文件后重试；已有资料不会被自动清空。";
    }
    private void clearSecrets(){
        if(expectedCode!=null){Arrays.fill(expectedCode,(byte)0);expectedCode=null;}
        if(recovery!=null)recovery.setText("");if(confirmCode!=null)confirmCode.setText("");if(restoreCode!=null)restoreCode.setText("");
        if(confirm!=null)confirm.setEnabled(false);
    }
    @Override public void onResume(){super.onResume();active=true;refresh();}
    @Override public void onPause(){active=false;++generation;cancelled.set(true);clearSecrets();setBusy(false);super.onPause();}
    @Override public void onStop(){active=false;++generation;cancelled.set(true);clearSecrets();setBusy(false);super.onStop();}
    @Override public void onDestroy(){cancelled.set(true);clearSecrets();super.onDestroy();}
}
