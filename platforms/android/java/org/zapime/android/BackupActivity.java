// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local data snapshots only. No network, account binding or sync key provisioning. */
public final class BackupActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView status,recovery;
    private EditText confirmCode,restoreCode;
    private Button create,choose,restore,confirm,copyCode,clearClipboard;
    private byte[] copiedDigest;private String copiedLabel;
    private boolean active,busy;private int generation,pendingOperation;
    private Uri pendingUri,restoreUri,lastExportUri;
    private String exportName;
    private byte[] expectedCode;
    private AtomicBoolean cancelled=new AtomicBoolean();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page=Ui.page(this);page.addView(Ui.text(this,"加密备份与恢复",27,Ui.INK));
        page.addView(Ui.text(this,"备份个人词库、候选规则、短语、收藏、剪贴板及逻辑删除记录。全程在本机处理；恢复仅接受没有资料、账号绑定和同步历史的空库。",14,Ui.MUTED));
        page.addView(Ui.text(this,"本文件不含设备授权、云同步密钥与历史、失败页、语音模型或系统设置。它不是云账号恢复包。初版资料归档上限4 MiB，加密文件上限6 MiB。",13,Ui.MUTED));
        status=Ui.text(this,"从本机资料页进入；若尚未初始化，请返回等待资料页打开完成。",14,Ui.BLUE);page.addView(status);
        LinearLayout save=Ui.card(page);save.addView(Ui.text(this,"创建加密备份",20,Ui.INK));
        create=Ui.button(this,"选择位置并创建加密备份",Ui.BLUE,true);Ui.addButton(save,create);create.setOnClickListener(v->{if(busy)return;clearSecrets();exportName="ZapIME-local-"+System.currentTimeMillis()+".json";picker(Intent.ACTION_CREATE_DOCUMENT,10,exportName);});
        save.addView(Ui.text(this,"文件校验后才显示恢复码。请抄写或显式复制到可信密码管理器，与文件分开保存。切换应用会清除本页恢复码；复制不会证明已经保存。",13,Ui.MUTED));
        recovery=Ui.text(this,"",17,Ui.INK);recovery.setTypeface(android.graphics.Typeface.MONOSPACE);recovery.setSaveEnabled(false);recovery.setLongClickable(false);save.addView(recovery);
        copyCode=Ui.button(this,"复制恢复码…",Ui.BG,false);Ui.addButton(save,copyCode);copyCode.setEnabled(false);copyCode.setOnClickListener(v->{if(expectedCode==null||busy)return;new AlertDialog.Builder(this).setTitle("复制恢复码？").setMessage("系统剪贴板可能被其他应用读取。仅在可信环境使用，粘贴到密码管理器后请返回清除，或从系统剪贴板手动删除。切换应用会清除本页显示，但不会自动清除系统剪贴板。").setNegativeButton("取消",null).setPositiveButton("复制恢复码",(d,w)->copyRecovery()).show();});
        clearClipboard=Ui.button(this,"清除本次复制的恢复码",Ui.BG,false);Ui.addButton(save,clearClipboard);clearClipboard.setEnabled(false);clearClipboard.setOnClickListener(v->clearCopiedRecovery());
        confirmCode=secret("也可留在本页抄写后，输入43字符核对");save.addView(confirmCode);
        confirm=Ui.button(this,"核对恢复码",Ui.BG,false);Ui.addButton(save,confirm);confirm.setEnabled(false);confirm.setOnClickListener(v->{
            if(expectedCode==null)return;byte[] entered=null;try{entered=code(confirmCode);if(!MessageDigest.isEqual(expectedCode,entered))throw new IOException();clearSecrets();status.setText("恢复码核对一致。请将恢复码与备份文件分开妥善保存。");}catch(IOException invalid){status.setText("恢复码不一致，请核对已另存的43个字符。");}finally{if(entered!=null)Arrays.fill(entered,(byte)0);}
        });
        LinearLayout load=Ui.card(page);load.addView(Ui.text(this,"恢复本机资料",20,Ui.INK));
        choose=Ui.button(this,"选择加密备份文件",Ui.BG,false);Ui.addButton(load,choose);choose.setOnClickListener(v->{if(!busy)picker(Intent.ACTION_OPEN_DOCUMENT,11,null);});
        restoreCode=secret("输入该备份文件对应的恢复码");load.addView(restoreCode);
        restore=Ui.button(this,"验证并恢复到空资料库",Ui.BLUE,true);Ui.addButton(load,restore);restore.setOnClickListener(v->{if(busy||restoreUri==null){status.setText("请先选择加密备份文件。");return;}
            new AlertDialog.Builder(this).setTitle("恢复本机资料？").setMessage("只接受无资料、账号绑定或同步历史的空库；不覆盖现有内容，不恢复账号授权或同步密钥。验证成功后一次事务导入。").setNegativeButton("取消",null).setPositiveButton("验证并恢复",(d,w)->restore()).show();
        });Ui.showPage(this,page);
    }
    private EditText secret(String hint){EditText value=new EditText(this);value.setHint(hint);value.setSingleLine(true);value.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);value.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);value.setSaveEnabled(false);value.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);value.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(43)});Ui.field(value);return value;}
    private static byte[] code(EditText input)throws IOException {if(input.length()!=43)throw new IOException("backup_code_format");byte[] bytes=new byte[43];for(int n=0;n<43;n++){char c=input.getText().charAt(n);if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-')){Arrays.fill(bytes,(byte)0);throw new IOException("backup_code_format");}bytes[n]=(byte)c;}return bytes;}
    private void picker(String action,int request,String name){Intent intent=new Intent(action).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);if(name!=null)intent.putExtra(Intent.EXTRA_TITLE,name);intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));startActivityForResult(intent,request);}
    @Override protected void onActivityResult(int operation,int result,Intent intent){super.onActivityResult(operation,result,intent);if(result!=RESULT_OK||intent==null||intent.getData()==null)return;pendingOperation=operation;pendingUri=intent.getData();if(active)pending();}
    @Override public void onResume(){super.onResume();active=true;pending();}
    private void pending(){if(pendingUri==null)return;Uri uri=pendingUri;int op=pendingOperation;pendingUri=null;if(op==10){lastExportUri=uri;export(uri);}else if(op==11){restoreUri=uri;status.setText("已选择文件。请输入对应恢复码，再显式恢复。");}}
    private void busy(boolean value){busy=value;for(Button b:new Button[]{create,choose,restore})b.setEnabled(!value);confirm.setEnabled(!value&&expectedCode!=null);copyCode.setEnabled(!value&&expectedCode!=null);confirmCode.setEnabled(!value);restoreCode.setEnabled(!value);}
    private void export(Uri uri){
        final int ticket=++generation;final AtomicBoolean stop=new AtomicBoolean();cancelled=stop;busy(true);status.setText("正在创建并写后校验加密备份…");
        try{BackupWorker.EXECUTOR.execute(()->{byte[] code=new byte[43],encrypted=null;boolean handed=false;
            try{BackupFiles.check(stop::get);encrypted=LocalBackup.create(getApplicationContext(),code);BackupFiles.check(stop::get);
                BackupFiles.writeVerified(new BackupFiles.Document(){public OutputStream write()throws IOException{return getContentResolver().openOutputStream(uri,"wt");}public InputStream read()throws IOException{return getContentResolver().openInputStream(uri);}},encrypted,stop::get);
                final byte[] shown=code;main.post(()->{if(!active||ticket!=generation){Arrays.fill(shown,(byte)0);return;}expectedCode=shown;recovery.setText(new String(shown,StandardCharsets.US_ASCII));busy(false);status.setText("加密文件已写入并读回校验。请抄写或显式复制恢复码；可在离开前输入核对。");});handed=true;
            }catch(Exception|LinkageError failure){reply(ticket,message(failure));}
            finally{if(!handed)Arrays.fill(code,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
        });}catch(RejectedExecutionException full){reply(ticket,"备份任务仍在处理，请稍后重试。");}
    }
    private void restore(){
        byte[] entered;try{entered=code(restoreCode);}catch(IOException invalid){status.setText("恢复码须为43个英文、数字、下划线或短横线字符。");return;}restoreCode.setText("");final Uri uri=restoreUri;final int ticket=++generation;final AtomicBoolean stop=new AtomicBoolean();cancelled=stop;busy(true);status.setText("正在认证并恢复资料…");
        try{BackupWorker.EXECUTOR.execute(()->{byte[] encrypted=null;try{BackupFiles.check(stop::get);try(InputStream in=getContentResolver().openInputStream(uri)){encrypted=BackupFiles.read(in,stop::get);}BackupFiles.check(stop::get);int count=LocalBackup.restore(getApplicationContext(),entered,encrypted);main.post(()->{if(!active||ticket!=generation)return;busy(false);setResult(RESULT_OK);status.setText("已恢复 "+count+" 条本机资料；未启用云同步。");});}catch(Exception|LinkageError failure){reply(ticket,message(failure));}finally{Arrays.fill(entered,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}});}catch(RejectedExecutionException full){Arrays.fill(entered,(byte)0);reply(ticket,"备份任务仍在处理，请稍后重试。");}
    }
    private void reply(int ticket,String text){main.post(()->{if(active&&ticket==generation){busy(false);status.setText(text);}});}
    private static String message(Throwable error){String code=error.getMessage();if("restore_requires_fresh_local_profile".equals(code))return "未导入：资料库已有资料、账号绑定或历史。请在全新空资料库恢复，现有内容保持不变。";if("invalid_or_unauthenticated_backup".equals(code)||"local_backup_invalid_archive".equals(code))return "恢复码不正确、文件损坏或格式不支持，未导入资料。";if("backup_cancelled".equals(code))return "操作已停止，请重新选择文件。";if("backup_readback_failed".equals(code))return "写后校验失败，未完成备份；请换位置重新创建。";if("backup_snapshot_unavailable".equals(code)||"local_profile_unavailable".equals(code))return "无法读取本机资料，或归档超过4 MiB。请返回资料页完成初始化并检查资料。";return "文件操作未完成。原资料未被自动清空；请检查文件位置和格式后重试。";}
    private void clearSecrets(){if(expectedCode!=null){Arrays.fill(expectedCode,(byte)0);expectedCode=null;}if(recovery!=null)recovery.setText("");if(confirmCode!=null)confirmCode.setText("");if(restoreCode!=null)restoreCode.setText("");if(confirm!=null)confirm.setEnabled(false);if(copyCode!=null)copyCode.setEnabled(false);}
    private void copyRecovery(){
        if(!active||busy||expectedCode==null)return;
        byte[] digest=null;
        try{
            digest=MessageDigest.getInstance("SHA-256").digest(expectedCode);
            String label="ZapIME recovery "+java.util.UUID.randomUUID();
            ClipData clip=ClipData.newPlainText(label,new String(expectedCode,StandardCharsets.US_ASCII));
            PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
            forgetCopiedFingerprint();copiedDigest=digest;digest=null;copiedLabel=label;clearClipboard.setEnabled(true);
            status.setText("已按你的确认复制恢复码，并标记为敏感内容。请粘贴到可信位置后清除；复制不代表外部保存成功。");
        }catch(Exception unavailable){status.setText("未能复制恢复码。请留在本页抄写，或重新创建备份。");}
        finally{if(digest!=null)Arrays.fill(digest,(byte)0);}
    }
    private void clearCopiedRecovery(){
        if(copiedDigest==null||copiedLabel==null)return;
        byte[] current=null,digest=null;
        try{
            ClipboardManager manager=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=manager.getPrimaryClip();
            if(clip==null||clip.getItemCount()!=1||!copiedLabel.contentEquals(clip.getDescription().getLabel()==null?"":clip.getDescription().getLabel())){status.setText("剪贴板已变化，保留当前内容。");forgetCopiedFingerprint();return;}
            CharSequence text=clip.getItemAt(0).getText();if(text==null||text.length()!=43){status.setText("剪贴板已变化，保留当前内容。");forgetCopiedFingerprint();return;}
            current=new byte[43];for(int n=0;n<43;n++){char c=text.charAt(n);if(c>127){status.setText("剪贴板已变化，保留当前内容。");forgetCopiedFingerprint();return;}current[n]=(byte)c;}
            digest=MessageDigest.getInstance("SHA-256").digest(current);
            if(MessageDigest.isEqual(copiedDigest,digest)){manager.clearPrimaryClip();status.setText("已清除本次复制的恢复码；请确认可信位置已经保存。");}else status.setText("剪贴板已变化，保留当前内容。");
            forgetCopiedFingerprint();
        }catch(Exception unavailable){status.setText("无法核实或清除剪贴板，请在系统剪贴板手动检查。");}
        finally{if(current!=null)Arrays.fill(current,(byte)0);if(digest!=null)Arrays.fill(digest,(byte)0);}
    }
    private void forgetCopiedFingerprint(){if(copiedDigest!=null)Arrays.fill(copiedDigest,(byte)0);copiedDigest=null;copiedLabel=null;if(clearClipboard!=null)clearClipboard.setEnabled(false);}
    @Override public void onStop(){active=false;++generation;cancelled.set(true);clearSecrets();busy(false);super.onStop();}
    @Override public void onDestroy(){clearSecrets();forgetCopiedFingerprint();super.onDestroy();}
}
