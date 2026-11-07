// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One physical inference at a time, with only the latest pending snapshot. */
final class CandidateRankingWorker implements AutoCloseable {
    interface Backend {
        float[] score(String context, String[] candidates) throws Exception;
        void clear() throws Exception;
        /** Must be nonblocking; the only backend operation allowed off-owner. */
        void cancel();
        void close() throws Exception;
    }

    interface Listener {
        void completed(Request request, String[] orderedIds, String state);
    }

    static final class Request {
        final int epoch;
        final long generation, inputRevision, contextVersion, deadlineNanos;
        final String context;
        private final String[] ids, texts;

        Request(int epoch, long generation, long inputRevision, long contextVersion,
                String context, String[] ids, String[] texts, long budgetMillis) {
            if (context == null || !CandidateRankingContext.validUtf16(context)
                    || context.codePointCount(0, context.length()) > CandidateRankingContext.MAX_CODE_POINTS
                    || ids == null || texts == null || ids.length < 2 || ids.length > 32
                    || ids.length != texts.length || budgetMillis <= 0 || budgetMillis > 2000) {
                throw new IllegalArgumentException("candidate_request_bounds");
            }
            HashSet<String> unique = new HashSet<>();
            for (int i = 0; i < ids.length; ++i) {
                if (ids[i] == null || ids[i].isEmpty() || ids[i].length() > 64 || !unique.add(ids[i])
                        || texts[i] == null || texts[i].isEmpty()
                        || !CandidateRankingContext.validUtf16(texts[i])
                        || texts[i].codePointCount(0, texts[i].length()) > 64) {
                    throw new IllegalArgumentException("candidate_request_shape");
                }
            }
            this.epoch = epoch;
            this.generation = generation;
            this.inputRevision = inputRevision;
            this.contextVersion = contextVersion;
            this.context = context;
            this.ids = ids.clone();
            this.texts = texts.clone();
            this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        }

        boolean expired() { return System.nanoTime() >= deadlineNanos; }
    }

    private static final class Job {
        final long serial;
        final Request request;
        volatile boolean expired;
        Job(long serial, Request request) { this.serial = serial; this.request = request; }
    }

    private static final class Completion {
        final Job job;
        final String[] ids;
        final String state;
        Completion(Job job, String[] ids, String state) {
            this.job = job; this.ids = ids; this.state = state;
        }
    }

    private final Object lock = new Object();
    private final Backend backend;
    private final Executor callbacks;
    private final Listener listener;
    private final Thread thread;
    private final ScheduledThreadPoolExecutor watchdog;
    private Job pending, active;
    private Completion completed;
    private boolean closed, clearRequested = true, releaseRequested, callbackQueued;
    private long serial, lastContextVersion = Long.MIN_VALUE;

    CandidateRankingWorker(Backend backend, Executor callbacks, Listener listener) {
        this.backend = backend;
        this.callbacks = callbacks;
        this.listener = listener;
        watchdog = new ScheduledThreadPoolExecutor(1, task -> {
            Thread timer = new Thread(task, "zapime-candidate-deadline");
            timer.setDaemon(true);
            return timer;
        });
        watchdog.setRemoveOnCancelPolicy(true);
        watchdog.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        thread = new Thread(this::run, "zapime-candidate-ranking");
        thread.setDaemon(true);
        thread.start();
    }

    void submit(Request request) {
        synchronized (lock) {
            if (closed) return;
            completed = null;
            pending = new Job(++serial, request);
            cancelActive();
            lock.notifyAll();
        }
    }

    /** Discard old work immediately; native capacity remains held until exit. */
    void invalidate(boolean clearContext, boolean releaseModel) {
        synchronized (lock) {
            if (closed) return;
            ++serial;
            pending = null;
            completed = null;
            clearRequested |= clearContext;
            releaseRequested |= releaseModel;
            cancelActive();
            lock.notifyAll();
        }
    }

    private void cancelActive() {
        if (active == null) return;
        clearRequested = true;
        try { backend.cancel(); } catch (RuntimeException | LinkageError ignored) { }
    }

    private boolean current(Job job) {
        synchronized (lock) { return !closed && serial == job.serial && !job.expired; }
    }

    private ScheduledFuture<?> deadline(Job job) {
        synchronized (lock) {
            if (closed) return null;
            // There is at most one active native job and one timer. Cancellation
            // never releases its physical slot; only the owner returning does.
            return watchdog.schedule(() -> {
                synchronized (lock) {
                    if (closed || active != job) return;
                    job.expired = true;
                    clearRequested = true;
                    try { backend.cancel(); } catch (RuntimeException | LinkageError ignored) { }
                }
            }, Math.max(0, job.request.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
    }

    private void run() {
        try {
            while (true) {
                Job job;
                boolean release, clear;
                synchronized (lock) {
                    while (!closed && pending == null && !releaseRequested && !clearRequested) {
                        try { lock.wait(); }
                        catch (InterruptedException interrupted) { closed = true; }
                    }
                    if (closed) return;
                    release = releaseRequested;
                    releaseRequested = false;
                    clear = clearRequested;
                    clearRequested = false;
                    job = pending;
                    pending = null;
                    active = job;
                }
                if (release) {
                    closeBackend();
                    lastContextVersion = Long.MIN_VALUE;
                } else if (clear && job == null) {
                    clearBackend();
                    lastContextVersion = Long.MIN_VALUE;
                }
                if (job == null) continue;
                String state = "unavailable";
                String[] order = null;
                ScheduledFuture<?> timeout = deadline(job);
                try {
                    if (current(job)) {
                        if (clear || release || job.request.contextVersion != lastContextVersion) {
                            backend.clear();
                            lastContextVersion = job.request.contextVersion;
                        }
                        if (current(job) && System.nanoTime() < job.request.deadlineNanos) {
                            float[] scores = backend.score(job.request.context, job.request.texts.clone());
                            order = ordered(job.request, scores);
                            state = order == null ? "unavailable" : "ready";
                        } else state = "expired";
                    }
                } catch (Exception | LinkageError failure) {
                    // Never log messages: native/runtime exceptions may contain
                    // inputs or filesystem paths. The next task starts clean.
                    synchronized (lock) { clearRequested = true; }
                    state = "unavailable";
                } finally {
                    if (timeout != null) timeout.cancel(false);
                }
                if (job.expired || System.nanoTime() >= job.request.deadlineNanos) {
                    order = null;
                    state = "expired";
                }
                synchronized (lock) { active = null; }
                post(job, order, state);
            }
        } finally {
            watchdog.shutdownNow();
            closeBackend();
            synchronized (lock) { active = null; pending = null; completed = null; }
        }
    }

    private void clearBackend() {
        try { backend.clear(); }
        catch (Exception | LinkageError failure) { closeBackend(); }
    }

    private void closeBackend() {
        try { backend.close(); } catch (Exception | LinkageError ignored) { }
    }

    private static String[] ordered(Request request, float[] scores) {
        if (scores == null || scores.length != request.ids.length) return null;
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < scores.length; ++i) {
            if (Float.isNaN(scores[i]) || Float.isInfinite(scores[i])) return null;
            order[i] = i;
        }
        // Explicit original-index ties keep stable candidate identities even
        // when two scores differ only by the sign of zero.
        Arrays.sort(order, (a, b) -> scores[a] == scores[b]
                ? Integer.compare(a, b) : Float.compare(scores[b], scores[a]));
        String[] ids = new String[order.length];
        for (int i = 0; i < order.length; ++i) ids[i] = request.ids[order[i]];
        return ids;
    }

    private void post(Job job, String[] ids, String state) {
        synchronized (lock) {
            if (closed || serial != job.serial) return;
            completed = new Completion(job, ids, state);
            if (callbackQueued) return;
            callbackQueued = true;
        }
        try {
            callbacks.execute(() -> {
                Completion value;
                synchronized (lock) {
                    callbackQueued = false;
                    value = completed;
                    completed = null;
                    if (value == null || closed || serial != value.job.serial) return;
                }
                boolean expired = value.job.request.expired();
                listener.completed(value.job.request, expired ? null : value.ids, expired ? "expired" : value.state);
            });
        } catch (RuntimeException unavailable) {
            synchronized (lock) { callbackQueued = false; completed = null; }
        }
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            ++serial;
            pending = null;
            completed = null;
            cancelActive();
            watchdog.shutdownNow();
            lock.notifyAll();
        }
    }

    boolean awaitTermination(long milliseconds) throws InterruptedException {
        thread.join(milliseconds);
        return !thread.isAlive();
    }
}
