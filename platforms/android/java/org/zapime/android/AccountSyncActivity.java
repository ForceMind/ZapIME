// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

public final class AccountSyncActivity extends Activity {
    private static final String DATA_SCOPE = "同步范围包括已有和以后新增或修改的个人词库、快捷短语、候选规则、永久收藏和手动保存的剪贴板。";
    private static final class PendingLogin {
        final AccountLoginProtocol.Attempt attempt;
        final boolean requestSync;
        final UUID generation;

        PendingLogin(AccountLoginProtocol.Config config, boolean requestSync, UUID generation) {
            this.attempt = new AccountLoginProtocol.Attempt(config, SystemClock.elapsedRealtime());
            this.requestSync = requestSync;
            this.generation = generation;
        }
    }

    private AccountLoginProtocol.Config config;
    private PendingLogin pending;
    private SyncHttp.Cancellation exchange;
    private Button enable, login, cancel;
    private Button restoreKey, checkKey, loginAgain, signOut;
    private Switch syncEnabled;
    private LinearLayout loginCard;
    private TextView status;
    private SyncConsentController.Assessment presented;
    private boolean readingStatus;

    private interface AssessmentAction {
        SyncConsentController.Assessment run(SyncHttp.Cancellation cancel) throws IOException;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = AccountLoginDeployment.config();
        if (config == null) {
            finish();
            return;
        }
        ForegroundSync.install(getApplication(), config);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout page = Ui.page(this);
        page.addView(Ui.text(this, "账号与同步", 28, Ui.INK));
        loginCard = Ui.card(page);
        loginCard.addView(Ui.text(this, "服务器 " + config.syncServer, 14, Ui.MUTED));
        loginCard.addView(Ui.text(this, "登录后可以开启加密同步", 20, Ui.INK));
        loginCard.addView(Ui.text(this, DATA_SCOPE, 14, Ui.MUTED));
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
        syncEnabled = new Switch(this);
        syncEnabled.setText("加密同步");
        syncEnabled.setOnClickListener(view -> chooseSync());
        progress.addView(syncEnabled);
        restoreKey = Ui.button(this, "恢复原同步密钥", Ui.BLUE, true);
        restoreKey.setOnClickListener(view -> {
            if (presented == null || presented.status != SyncConsentController.Status.WAITING_FOR_KEY) return;
            startActivity(new Intent(this, SyncPreparationActivity.class)
                    .putExtra(SyncPreparationActivity.CONSENT_GENERATION, presented.state.generation.toString()));
        });
        Ui.addButton(progress, restoreKey);
        checkKey = Ui.button(this, "核对服务器密钥", Ui.BG, false);
        checkKey.setOnClickListener(view -> startActivity(new Intent(this, SyncKeyRegistrationActivity.class)));
        Ui.addButton(progress, checkKey);
        loginAgain = Ui.button(this, "重新登录", Ui.BG, false);
        loginAgain.setOnClickListener(view -> startLogin(false));
        Ui.addButton(progress, loginAgain);
        signOut = Ui.button(this, "退出登录", Ui.BG, false);
        signOut.setOnClickListener(view -> signOut());
        Ui.addButton(progress, signOut);
        cancel = Ui.button(this, "取消登录", Ui.BG, false);
        cancel.setOnClickListener(view -> {
            cancelWork();
            presented = null;
            readStatus();
        });
        Ui.addButton(progress, cancel);
        Ui.showPage(this, page);

        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof PendingLogin) pending = (PendingLogin) retained;
        update(pending == null ? "同步已关闭" : "请在浏览器中完成登录", pending != null);
        acceptIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        if (config != null && pending == null && exchange == null) {
            SyncConsentStore.State running = ForegroundSync.activeConsent();
            if (presented == null && running != null) {
                render(new SyncConsentController.Assessment(running, SyncConsentController.Status.CHECKING), null);
            }
            readStatus();
        }
    }

    private void readStatus() {
        readingStatus = true;
        work("正在核对本机同步状态", request -> SyncConsentController.inspect(getApplicationContext(), config, request));
    }

    private void chooseSync() {
        if (presented == null || (exchange != null && !readingStatus)) return;
        SyncConsentStore.State shown = presented.state;
        syncEnabled.setChecked(shown.expectedKey != null);
        if (shown.expectedKey != null) {
            if (readingStatus) cancelWork();
            pauseGeneration(shown.generation);
            work("正在保存关闭状态", request -> {
                request.check();
                SyncConsentStore.disable(getApplicationContext(), shown.generation);
                return SyncConsentController.inspect(getApplicationContext(), config, request);
            });
            return;
        }
        new AlertDialog.Builder(this).setTitle("开启加密同步")
                .setMessage("服务器 " + config.syncServer + "\n账号 " + shown.account + "\n设备 " + shown.device
                        + "\n\n" + DATA_SCOPE)
                .setNegativeButton("取消", null)
                .setPositiveButton("开启加密同步", (dialog, button) -> work("正在核对设备和密钥",
                        request -> SyncConsentController.enable(getApplicationContext(), config, shown, request)))
                .show();
    }

    private void work(String progress, AssessmentAction action) {
        if (pending != null || exchange != null) return;
        SyncHttp.Cancellation request = new SyncHttp.Cancellation();
        exchange = request;
        update(progress, true);
        try {
            SyncWorker.EXECUTOR.execute(() -> {
                try {
                    SyncConsentController.Assessment result = action.run(request);
                    runOnUiThread(() -> {
                        if (exchange != request || isDestroyed()) return;
                        exchange = null;
                        readingStatus = false;
                        render(result, null);
                    });
                } catch (IOException | RuntimeException failure) {
                    runOnUiThread(() -> {
                        if (exchange != request || isDestroyed()) return;
                        exchange = null;
                        boolean unavailable = readingStatus && failure instanceof SyncHttp.Failure
                                && ("network".equals(((SyncHttp.Failure) failure).code)
                                    || "timeout".equals(((SyncHttp.Failure) failure).code));
                        readingStatus = false;
                        update(unavailable ? "无法连接同步服务器。待发操作保留。"
                                : "暂时无法核对或保存同步状态，请返回后重试。", false);
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            exchange = null;
            readingStatus = false;
            update("同步页面正忙，状态尚未保存。请稍后重试。", false);
        }
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
        if (presented != null && presented.state.account != null) pauseGeneration(presented.state.generation);
        SyncHttp.Cancellation request = new SyncHttp.Cancellation();
        exchange = request;
        update("正在准备登录", true);
        try {
            SyncWorker.EXECUTOR.execute(() -> {
                try {
                    SyncConsentStore.State login = SyncConsentStore.beginLogin(getApplicationContext(), request);
                    runOnUiThread(() -> {
                        if (isDestroyed() || exchange != request) return;
                        exchange = null;
                        presented = null;
                        loginCard.setVisibility(View.VISIBLE);
                        launchBrowser(requestSync, login.generation);
                    });
                } catch (IOException | RuntimeException failure) {
                    runOnUiThread(() -> {
                        if (isDestroyed() || exchange != request) return;
                        exchange = null;
                        update("无法保存本机登录状态，请重试。", false);
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            exchange = null;
            update("同步页面正忙，请稍后重试。", false);
        }
    }

    private void signOut() {
        if (presented == null || presented.state.account == null || pending != null
                || (exchange != null && !readingStatus)) return;
        UUID generation = presented.state.generation;
        if (readingStatus) cancelWork();
        pauseGeneration(generation);
        work("同步已暂停，正在退出登录", request -> {
            SyncConsentStore.State signedOut = SyncConsentStore.signOut(getApplicationContext(), generation, request);
            return new SyncConsentController.Assessment(signedOut, SyncConsentController.Status.SIGNED_OUT);
        });
    }

    private static void pauseGeneration(UUID generation) {
        SyncConsentController.stop(generation);
        ForegroundSync.pause();
    }

    private void launchBrowser(boolean requestSync, UUID generation) {
        pending = new PendingLogin(config, requestSync, generation);
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
        final UUID generation = pending.generation;
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
                    SyncConsentStore.State stored = SyncConsentStore.completeLogin(getApplicationContext(), generation,
                            identity.enrollmentScope, identity.device, request);
                    SyncConsentController.Assessment result;
                    String notice = null;
                    try {
                        result = requestSync && identity.device != null
                                ? SyncConsentController.enable(getApplicationContext(), config, stored, request)
                                : SyncConsentController.inspect(getApplicationContext(), config, request);
                    } catch (IOException unavailable) {
                        request.check();
                        result = SyncConsentController.inspect(getApplicationContext(), config, request);
                        notice = "暂时无法开启。请核对设备授权和原同步密钥。";
                    }
                    final SyncConsentController.Assessment rendered = result;
                    final String message = notice;
                    runOnUiThread(() -> {
                        if (exchange != request || isDestroyed()) return;
                        exchange = null;
                        render(rendered, message);
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

    private void render(SyncConsentController.Assessment assessment, String notice) {
        boolean newlyReady = assessment.status == SyncConsentController.Status.READY
                && (presented == null || presented.status != SyncConsentController.Status.READY
                    || !assessment.state.generation.equals(presented.state.generation));
        presented = assessment;
        SyncConsentStore.State state = assessment.state;
        loginCard.setVisibility(state.account == null ? View.VISIBLE : View.GONE);
        String message;
        switch (assessment.status) {
            case WAITING_FOR_DEVICE: message = "此设备尚未获授权\n同步已关闭"; break;
            case WAITING_FOR_KEY: message = "需要恢复同步密钥"; break;
            case CHECKING: message = "正在核对设备和密钥"; break;
            case PAUSED: message = "本机同步已暂停\n关闭状态尚未保存"; break;
            case WORKSPACE_REQUIRED: message = "本机资料库需要完成密钥准备\n同步已关闭"; break;
            case KEY_CONFLICT: message = "本机与服务器的密钥不一致\n同步已关闭"; break;
            case KEY_UNREGISTERED: message = "服务器尚未登记同步密钥\n同步已关闭"; break;
            case REVOKED: message = "设备授权失效\n同步已关闭"; break;
            case READY:
                ForegroundSync.Outcome outcome = ForegroundSync.last(state.generation);
                switch (ForegroundSync.phase(state.generation)) {
                    case OFFLINE: message = "等待网络恢复"; break;
                    case CHECKING: message = "正在检查已有资料"; break;
                    case SENDING: message = "正在同步，等待服务器回执"; break;
                    default: message = outcome == null ? "设备和密钥已核对\n等待同步任务" : syncOutcome(outcome);
                }
                break;
            default: message = "同步已关闭";
        }
        String target = state.account == null ? "" : "已登录\n服务器 " + config.syncServer +
                "\n账号 " + state.account + (state.device == null ? "" : "\n设备 " + state.device) + "\n";
        update(target + message + (notice == null ? "" : "\n" + notice), false);
        if (newlyReady) ForegroundSync.ready();
    }

    void onAutomaticResult(ForegroundSync.Outcome outcome) {
        if (presented != null && exchange == null && presented.status == SyncConsentController.Status.READY
                && presented.state.generation.equals(outcome.generation)) render(presented, null);
    }

    void onAutomaticProgress() {
        if (presented != null && exchange == null && presented.status == SyncConsentController.Status.READY) {
            render(presented, null);
        }
    }

    private static String syncOutcome(ForegroundSync.Outcome outcome) {
        SyncRound.Result result = outcome.result;
        String receipt = "";
        if (result != null && result.lastAcknowledgedRevision > 0
                && result.lastAcknowledgedRevision >= result.lastReconciledRevision) {
            receipt = "\n服务器已确认本次写入，修订号 " + result.lastAcknowledgedRevision;
        } else {
            long previous = ForegroundSync.lastConfirmedRevision(outcome.generation);
            if (previous > 0) receipt = "\n服务器最近确认写入，修订号 " + previous;
        }
        if ("origin_unverified".equals(outcome.error)) return "有待发资料的来源需要核对" + receipt;
        if ("page_rejected".equals(outcome.error)) return "远端资料未通过核对，请检查密钥或冲突记录。本机资料已保留" + receipt;
        if ("revision_conflict".equals(outcome.error)) return "服务器资料已有更新，本机待发操作仍保留" + receipt;
        if ("device_revoked".equals(outcome.error)) return "设备授权需要重新核对" + receipt;
        if ("network".equals(outcome.error)) return "连接中断。待发操作和已冻结请求仍保留" + receipt;
        if (outcome.error != null) return "本轮同步未完成" + receipt;
        if (result.downloadBudgetReached || result.uploadBudgetReached) return "仍有待处理的同步任务" + receipt;
        return "本轮同步已检查\n完成时间 " + DateFormat.getDateTimeInstance().format(new Date(outcome.completedAt)) + receipt;
    }

    private void update(String message, boolean waiting) {
        status.setText(message);
        enable.setEnabled(!waiting);
        login.setEnabled(!waiting);
        cancel.setVisibility(waiting ? View.VISIBLE : View.GONE);
        cancel.setText("取消");
        SyncConsentController.Status shown = presented == null ? null : presented.status;
        SyncConsentStore.State consent = presented == null ? null : presented.state;
        boolean hasDevice = consent != null && consent.device != null && shown != SyncConsentController.Status.REVOKED
                && shown != SyncConsentController.Status.WAITING_FOR_DEVICE;
        syncEnabled.setVisibility(hasDevice ? View.VISIBLE : View.GONE);
        syncEnabled.setEnabled(hasDevice && (!waiting || (readingStatus && consent.expectedKey != null)));
        syncEnabled.setChecked(consent != null && consent.expectedKey != null);
        restoreKey.setVisibility(!waiting && shown == SyncConsentController.Status.WAITING_FOR_KEY ? View.VISIBLE : View.GONE);
        checkKey.setVisibility(!waiting && shown == SyncConsentController.Status.KEY_UNREGISTERED ? View.VISIBLE : View.GONE);
        loginAgain.setVisibility(!waiting && shown == SyncConsentController.Status.REVOKED ? View.VISIBLE : View.GONE);
        signOut.setVisibility(consent != null && consent.account != null ? View.VISIBLE : View.GONE);
        signOut.setEnabled(!waiting || readingStatus);
    }

    private void cancelWork() {
        if (pending != null) pending.attempt.cancel();
        if (exchange != null) exchange.cancel();
        pending = null;
        exchange = null;
        readingStatus = false;
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
