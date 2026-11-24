// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Observes a real Activity and real network callbacks in the disposable test APK. */
final class DefaultNetworkProbe {
    static void run(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        ForegroundSyncProbe.verifyRecovery(context, true);
        File path = new File(context.getCacheDir(), "a3-default-network.phase");
        require(!path.exists(), "network_marker_exists");
        AtomicFile marker = new AtomicFile(path);
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        require(manager != null && manager.getActiveNetwork() != null, "network_baseline_missing");
        AtomicInteger available = new AtomicInteger(), lost = new AtomicInteger();
        ConnectivityManager.NetworkCallback observer = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { available.incrementAndGet(); }
            @Override public void onLost(Network network) { lost.incrementAndGet(); }
        };
        AtomicBoolean paused = new AtomicBoolean();
        Application application = (Application) context.getApplicationContext();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity activity) {
                if (activity instanceof AccountSyncActivity) paused.set(true);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        };
        manager.registerDefaultNetworkCallback(observer);
        application.registerActivityLifecycleCallbacks(lifecycle);
        try {
            Activity activity = instrumentation.startActivitySync(new Intent(context, AccountSyncActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor(() -> available.get() > 0 && hasStatus(instrumentation, activity, "本轮同步已检查"));
            require(!paused.get(), "network_baseline_not_foreground");
            signal(marker, "ready");
            waitFor(() -> manager.getActiveNetwork() == null && lost.get() > 0
                    && hasStatus(instrumentation, activity, "等待网络恢复"));
            require(!paused.get(), "network_loss_left_foreground");
            int beforeRestore = available.get();
            ForegroundSyncProbe.queueNetworkRecovery(context);
            require(manager.getActiveNetwork() == null && !paused.get(), "network_pending_not_offline");
            signal(marker, "pending");
            waitFor(() -> manager.getActiveNetwork() != null && available.get() > beforeRestore
                    && hasStatus(instrumentation, activity, "本轮同步已检查")
                    && hasStatus(instrumentation, activity, "修订号 3"));
            require(!paused.get(), "network_restore_left_foreground");
            ForegroundSyncProbe.verifyNetworkRecovery(context, true);
            signal(marker, "acknowledged");
        } finally {
            application.unregisterActivityLifecycleCallbacks(lifecycle);
            manager.unregisterNetworkCallback(observer);
        }
    }

    private static boolean hasStatus(Instrumentation instrumentation, Activity activity, String text) {
        AtomicBoolean matched = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> matched.set(!activity.isDestroyed() && activity.hasWindowFocus()
                && containsText(activity.getWindow().getDecorView(), text)));
        return matched.get();
    }

    private static boolean containsText(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) view;
            for (int index = 0; index < children.getChildCount(); index++) {
                if (containsText(children.getChildAt(index), text)) return true;
            }
        }
        return false;
    }

    private static void waitFor(BooleanSupplier condition) throws IOException {
        long end = SystemClock.elapsedRealtime() + 40_000;
        while (SystemClock.elapsedRealtime() < end) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        throw new IOException("network_observation_timeout");
    }

    private static void signal(AtomicFile marker, String phase) throws IOException {
        FileOutputStream output = marker.startWrite();
        try {
            output.write(phase.getBytes(StandardCharsets.US_ASCII));
            marker.finishWrite(output);
        } catch (IOException failed) {
            marker.failWrite(output);
            throw failed;
        }
    }

    private static void require(boolean condition, String reason) throws IOException {
        if (!condition) throw new IOException(reason);
    }

    private DefaultNetworkProbe() {}
}
