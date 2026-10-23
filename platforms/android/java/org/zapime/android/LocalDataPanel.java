// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.content.Context;
import android.view.Gravity;
import android.widget.*;
import org.json.*;
import java.util.UUID;
import java.util.function.Consumer;

/** Bounded local summaries. Full insertion text is resolved by Rust at click time. */
final class LocalDataPanel {
    interface Host {
        void request(JSONObject input,Consumer<JSONObject> done);
        void search();
        void capture();
        void close();
    }
    final LinearLayout view;
    private final Context context;
    private final KeyboardTheme theme;
    private final Host host;
    private final LinearLayout tabs,rows;
    private final TextView info;
    private final Button previous,next;
    private int offset,nextOffset=-1,ticket;
    private boolean mutating;
    String kind,query;
    LocalDataPanel(Context c,KeyboardTheme t,Host h,String kind,String query) {
        context=c;theme=t;host=h;this.kind=kind;this.query=query;
        view=Ui.column(c);tabs=new LinearLayout(c);tabs.setBaselineAligned(false);view.addView(tabs,new LinearLayout.LayoutParams(-1,dp(44)));
        info=Ui.text(c,"正在读取本机资料…",12,t.muted);info.setSingleLine(true);info.setEllipsize(android.text.TextUtils.TruncateAt.END);info.setPadding(dp(8),0,dp(8),0);view.addView(info,new LinearLayout.LayoutParams(-1,dp(24)));
        ScrollView scroll=new ScrollView(c);rows=Ui.column(c);rows.setPadding(dp(6),0,dp(6),0);scroll.addView(rows);view.addView(scroll,new LinearLayout.LayoutParams(-1,dp(180)));
        LinearLayout footer=new LinearLayout(c);footer.setBaselineAligned(false);
        add(footer,button("搜索",()->host.search()),1);add(footer,button("保存剪贴板",()->host.capture()),1.4f);
        previous=button("上一页",()->{offset=Math.max(0,offset-20);load();});next=button("下一页",()->{offset=nextOffset;load();});
        previous.setEnabled(false);next.setEnabled(false);
        add(footer,previous,1);add(footer,next,1);add(footer,button("返回键盘",()->host.close()),1.2f);view.addView(footer,new LinearLayout.LayoutParams(-1,dp(44)));renderTabs();
    }
    private int dp(int n){return Ui.dp(context,n);}
    private Button button(String label,Runnable action){Button b=Ui.button(context,label,theme.special,false);b.setTextColor(theme.ink);b.setTextSize(12);b.setPadding(0,0,0,0);b.setContentDescription(label);b.setOnClickListener(v->{if(b.isAttachedToWindow())action.run();});return b;}
    private void add(LinearLayout row,Button button,float weight){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),weight);p.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(button,p);}
    private void renderTabs(){tabs.removeAllViews();String[] values={"all","snippet","favorite","clipboard"},labels={"全部资料","快捷短语","永久收藏","本机剪贴板"};for(int i=0;i<values.length;i++){final String value=values[i];Button b=button(labels[i],()->{kind=value;offset=0;renderTabs();load();});b.setSelected(kind.equals(value));if(b.isSelected()){b.setTextColor(theme.accent);b.setBackground(Ui.shape(context,theme.key,7));}add(tabs,b,1);}}
    void captured(){kind="clipboard";query="";offset=0;renderTabs();load();}
    void load(){final int request=++ticket;info.setText("正在读取本机资料…");try{host.request(new JSONObject().put("op","personal_query").put("kind",kind).put("query",query).put("offset",offset).put("limit",20),result->{if(request!=ticket)return;if(result.has("error")){info.setText("读取失败，请重新打开本机资料");return;}rows.removeAllViews();nextOffset=result.isNull("next_offset")?-1:result.optInt("next_offset",-1);offset=result.optInt("offset");previous.setEnabled(offset>0);next.setEnabled(nextOffset>=0);info.setText("本机 · 共 "+result.optInt("total")+" 条"+(query.isEmpty()?"":" · 搜索："+query));JSONArray items=result.optJSONArray("items");if(items==null||items.length()==0){TextView empty=Ui.text(context,query.isEmpty()?"暂无资料，可在工具里的个人资料中添加。":"没有匹配资料，试试其它词或编码。",14,theme.muted);empty.setPadding(dp(10),dp(20),dp(10),0);rows.addView(empty);return;}for(int i=0;i<items.length();i++)addRecord(items.optJSONObject(i));view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);});}catch(JSONException ignored){info.setText("查询未完成");}}
    private JSONObject action(JSONObject record,String operation)throws JSONException{return new JSONObject().put("op","personal_action").put("id",record.getString("id")).put("expected_revision",record.getLong("revision")).put("operation_id",UUID.randomUUID().toString()).put("action",operation);}
    private void mutate(JSONObject request){if(mutating)return;mutating=true;host.request(request,result->{mutating=false;if(result.has("error"))Toast.makeText(context,"资料已变化或操作未完成，请按最新列表重试",Toast.LENGTH_SHORT).show();else if(request.optString("action").equals("pin"))Toast.makeText(context,"置顶保留原过期时间，长期保存请转为收藏",Toast.LENGTH_LONG).show();load();});}
    private void addRecord(JSONObject record){if(record==null)return;LinearLayout card=Ui.column(context);card.setPadding(dp(6),dp(4),dp(6),dp(4));card.setBackground(Ui.shape(context,theme.key,8));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(6);rows.addView(card,cp);
        String label=record.optString("label"),preview=record.optString("preview"),type=record.optString("type");
        Button insert=Ui.button(context,label+"\n"+preview,theme.key,false);insert.setTextColor(theme.ink);insert.setTextSize(14);insert.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);insert.setMaxLines(3);insert.setEllipsize(android.text.TextUtils.TruncateAt.END);insert.setContentDescription("插入资料："+label+"，"+preview);card.addView(insert,new LinearLayout.LayoutParams(-1,dp(64)));
        insert.setOnClickListener(v->{if(!insert.isAttachedToWindow())return;insert.setEnabled(false);try{host.request(new JSONObject().put("op","personal_commit").put("id",record.getString("id")).put("expected_revision",record.getLong("revision")),result->{if(result.has("error"))Toast.makeText(context,"条目已变更、删除或过期，请重新选择",Toast.LENGTH_SHORT).show();load();});}catch(JSONException ignored){load();}});
        LinearLayout controls=new LinearLayout(context);controls.setBaselineAligned(false);card.addView(controls,new LinearLayout.LayoutParams(-1,dp(44)));
        if(type.equals("clipboard")){boolean pinned=record.optBoolean("pinned");Button pin=button(pinned?"取消置顶":"置顶",()->{try{mutate(action(record,pinned?"unpin":"pin"));}catch(JSONException ignored){}});add(controls,pin,1);}
        if(!type.equals("favorite")){Button favorite=button("转为收藏",()->{try{mutate(action(record,"favorite").put("destination_id",UUID.randomUUID().toString()));}catch(JSONException ignored){}});add(controls,favorite,1);}
        final boolean[] confirmed={false};Button delete=button("删除",()->{});delete.setOnClickListener(v->{if(!delete.isAttachedToWindow())return;if(!confirmed[0]){confirmed[0]=true;delete.setText("确认删除");delete.setContentDescription("确认删除");return;}delete.setEnabled(false);try{mutate(action(record,"delete"));}catch(JSONException ignored){load();}});add(controls,delete,1);
    }
}
