// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import android.app.Activity;
import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/** One physically running foreground round at a time, carrying its durable consent generation. */
final class ForegroundSync implements Application.ActivityLifecycleCallbacks {
    private static ForegroundSync instance;
    private final Application app;
    private final AccountLoginProtocol.Config config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> visible = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private Network network;
    private boolean connected, rerun;
    private SyncHttp.Cancellation running;
    private SyncConsentStore.State activeConsent;
    private Outcome last;

    static final class Outcome {
        final UUID generation;
        final SyncRound.Result result;
        final String error;

        Outcome(UUID generation, SyncRound.Result result, String error) {
            this.generation = generation;
            this.result = result;
            this.error = error;
        }
    }

    static void install(Application app, AccountLoginProtocol.Config config) {
        if (config != null && instance == null) {
            instance = new ForegroundSync(app, config);
            app.registerActivityLifecycleCallbacks(instance);
        }
    }

    static void ready() {
        if (instance != null) instance.request(true);
    }

    static void pause() {
        if (instance == null) return;
        instance.rerun = false;
        if (instance.running != null) instance.running.cancel();
    }

    static Outcome last(UUID generation) {
        if (instance == null || instance.last == null || !instance.last.generation.equals(generation)) return null;
        return instance.last;
    }

    static SyncConsentStore.State activeConsent() {
        return instance == null ? null : instance.activeConsent;
    }

    private ForegroundSync(Application app, AccountLoginProtocol.Config config) {
        this.app = app;
        this.config = config;
        connectivity = app.getSystemService(ConnectivityManager.class);
    }

    @Override public void onActivityStarted(Activity activity) {
        if (activity instanceof AccountLoginCallbackActivity) return;
        if (visible.add(activity) && visible.size() == 1) watchNetwork();
    }

    @Override public void onActivityStopped(Activity activity) {
        if (!visible.remove(activity) || !visible.isEmpty()) return;
        pause();
        connected = false;
        network = null;
        ConnectivityManager.NetworkCallback previous = callback;
        callback = null;
        if (previous != null) {
            try {
                connectivity.unregisterNetworkCallback(previous);
            } catch (IllegalArgumentException alreadyGone) {
                // The system may remove a callback before this Activity stops.
            }
        }
    }

    private void watchNetwork() {
        if (connectivity == null) {
            connected = true;
            request(true);
            return;
        }
        ConnectivityManager.NetworkCallback next = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network available) {
                if (callback != this) return;
                network = available;
                connected = true;
                request(true);
            }

            @Override public void onLost(Network lost) {
                if (callback != this || !lost.equals(network)) return;
                network = null;
                connected = false;
                pause();
            }
        };
        callback = next;
        try {
            connectivity.registerDefaultNetworkCallback(next, main);
        } catch (RuntimeException unavailable) {
            callback = null;
            connected = true;
            request(true);
        }
    }

    private void request(boolean foregroundOrNetworkChanged) {
        if (visible.isEmpty() || !connected) return;
        if (running != null) {
            rerun |= foregroundOrNetworkChanged;
            return;
        }
        SyncHttp.Cancellation cancel = new SyncHttp.Cancellation();
        running = cancel;
        try {
            SyncWorker.EXECUTOR.execute(() -> run(cancel));
        } catch (RejectedExecutionException busy) {
            running = null;
        }
    }

    private void run(SyncHttp.Cancellation cancel) {
        Outcome outcome = null;
        SyncConsentStore.State consent = null;
        try {
            cancel.check();
            consent = SyncConsentStore.load(app);
            if (consent.expectedKey != null && !SyncConsentController.isStopped(consent.generation)) {
                SyncConsentStore.State checked = consent;
                main.post(() -> {
                    if (running == cancel && !SyncConsentController.isStopped(checked.generation)) activeConsent = checked;
                });
                SyncRound.Result result = SyncTransfer.runAutomatic(app, config, consent.generation, cancel);
                outcome = new Outcome(consent.generation, result, null);
            }
        } catch (SyncRound.Failure incomplete) {
            if (consent != null) outcome = new Outcome(consent.generation, incomplete.completed, error(incomplete.getCause()));
        } catch (IOException | RuntimeException failed) {
            if (consent != null) outcome = new Outcome(consent.generation, null, error(failed));
        } finally {
            Outcome finished = outcome;
            main.post(() -> finish(cancel, finished));
        }
    }

    private void finish(SyncHttp.Cancellation cancel, Outcome outcome) {
        if (running != cancel) return;
        running = null;
        activeConsent = null;
        boolean again = rerun;
        rerun = false;
        if (!visible.isEmpty() && outcome != null && !SyncConsentController.isStopped(outcome.generation)) {
            last = outcome;
            for (Activity activity : new ArrayList<>(visible)) {
                if (activity instanceof AccountSyncActivity) ((AccountSyncActivity) activity).onAutomaticResult(outcome);
            }
        }
        if (again) request(false);
    }

    private static String error(Throwable failure) {
        return failure != null && "sync_operation_origin_unverified".equals(failure.getMessage())
                ? "origin_unverified" : "incomplete";
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
