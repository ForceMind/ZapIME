// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;

public final class AccountSyncActivity extends Activity {
    private static final class PendingLogin {
        final AccountLoginProtocol.Attempt attempt;
        final boolean requestSync;

        PendingLogin(AccountLoginProtocol.Config config, boolean requestSync) {
            this.attempt = new AccountLoginProtocol.Attempt(config, SystemClock.elapsedRealtime());
            this.requestSync = requestSync;
        }
    }

    private AccountLoginProtocol.Config config;
    private PendingLogin pending;
    private SyncHttp.Cancellation exchange;
    private Button enable, login, cancel;
    private LinearLayout loginCard;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = AccountLoginDeployment.config();
        if (config == null) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        page.addView(Ui.text(this, "账号与同步", 28, Ui.INK));
        loginCard = Ui.card(page);
        loginCard.addView(Ui.text(this, "服务器 " + config.syncServer, 14, Ui.MUTED));
        loginCard.addView(Ui.text(this, "登录后可以开启加密同步", 20, Ui.INK));
        loginCard.addView(Ui.text(this, "同步范围包括已有和以后新增或修改的个人词库、快捷短语、候选规则、永久收藏和手动保存的剪贴板。", 14, Ui.MUTED));
        enable = Ui.button(this, "登录并开启同步", Ui.BLUE, true);
        enable.setOnClickListener(view -> startLogin(true));
        Ui.addButton(loginCard, enable);
        login = Ui.button(this, "仅登录", Ui.BG, false);
        login.setOnClickListener(view -> startLogin(false));
        Ui.addButton(loginCard, login);
        LinearLayout progress = Ui.card(page);
        status = Ui.text(this, "", 15, Ui.INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress.addView(status);
        cancel = Ui.button(this, "取消登录", Ui.BG, false);
        cancel.setOnClickListener(view -> {
            cancelWork();
            update("登录已取消。同步已关闭。", false);
        });
        Ui.addButton(progress, cancel);
        Ui.showPage(this, page);

        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof PendingLogin) pending = (PendingLogin) retained;
        update(pending == null ? "同步已关闭" : "请在浏览器中完成登录", pending != null);
        acceptIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        acceptIntent(intent);
    }

    @Override public Object onRetainNonConfigurationInstance() {
        return pending;
    }

    @Override protected void onDestroy() {
        if (isChangingConfigurations()) {
            if (exchange != null) exchange.cancel();
        } else {
            cancelWork();
        }
        super.onDestroy();
    }

    private void startLogin(boolean requestSync) {
        if (pending != null || exchange != null) return;
        pending = new PendingLogin(config, requestSync);
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(pending.attempt.authorization().toString()));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            update("请在浏览器中完成登录", true);
            startActivity(browser);
        } catch (ActivityNotFoundException unavailable) {
            cancelWork();
            update("没有可用的浏览器。同步已关闭。", false);
        }
    }

    private void acceptIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        setIntent(new Intent(this, AccountSyncActivity.class));
        if (pending == null) return;
        final boolean requestSync = pending.requestSync;
        final AccountLoginProtocol.Exchange accepted;
        try {
            accepted = pending.attempt.accept(intent.getDataString(), SystemClock.elapsedRealtime());
        } catch (SyncHttp.Failure failure) {
            if (!failure.code.equals("login_callback")) {
                cancelWork();
                update(failure.code.equals("login_denied") ? "登录已取消。同步已关闭。" : "登录已过期，请重试。", false);
            }
            return;
        }
        pending = null;
        SyncHttp.Cancellation request = new SyncHttp.Cancellation();
        exchange = request;
        update("正在核对账号和设备授权", true);
        try {
            SyncWorker.EXECUTOR.execute(() -> {
                try {
                    AccountLoginExchange.Identity identity = AccountLoginExchange.verify(getApplicationContext(), accepted, request);
                    runOnUiThread(() -> {
                        if (exchange != request || isDestroyed()) return;
                        exchange = null;
                        loginCard.setVisibility(View.GONE);
                        String syncState = requestSync
                                ? (identity.device == null ? "同步等待设备授权" : "开启同步前需要核对密钥")
                                : "同步已关闭";
                        update("已登录\n服务器 " + config.syncServer + "\n账号 " + identity.account + "\n"
                                + identity.deviceState + (identity.device == null ? "" : "\n设备 " + identity.device)
                                + "\n" + syncState, false);
                    });
                } catch (IOException | RuntimeException failed) {
                    runOnUiThread(() -> {
                        if (exchange != request || isDestroyed()) return;
                        exchange = null;
                        update(loginFailure(failed), false);
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            exchange = null;
            update("同步页面正忙，请稍后重试。", false);
        }
    }

    private void update(String message, boolean waiting) {
        status.setText(message);
        enable.setEnabled(!waiting);
        login.setEnabled(!waiting);
        cancel.setVisibility(waiting ? View.VISIBLE : View.GONE);
    }

    private void cancelWork() {
        if (pending != null) pending.attempt.cancel();
        if (exchange != null) exchange.cancel();
        pending = null;
        exchange = null;
    }

    private static String loginFailure(Exception failure) {
        if (failure instanceof SyncHttp.Failure) {
            switch (((SyncHttp.Failure) failure).code) {
                case "unauthorized":
                case "forbidden":
                    return "此服务器账号尚未获授权。同步已关闭。";
                case "tls":
                    return "无法验证登录或同步服务器的 HTTPS 证书。请核对服务器。";
                case "unsupported":
                    return "此服务器尚未启用账号登录。同步已关闭。";
            }
        }
        return "无法完成登录，请检查网络后重试。同步已关闭。";
    }
}
