// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.test;

import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;

/** API30 types are isolated from the existing API28 instrumentation path. */
final class ConfigurationContinuityObserver implements AutoCloseable {
    private final ProbeActivity host;
    private final View decor;
    volatile int hiddenObservations, windowFocusLosses, editorFocusLosses, observations,visibleObservations;
    private final ViewTreeObserver.OnWindowFocusChangeListener window = focused -> {
        if (!focused) ++windowFocusLosses;
    };
    private final ViewTreeObserver.OnGlobalFocusChangeListener editor;
    ConfigurationContinuityObserver(ProbeActivity host) {
        this.host = host;
        editor = (oldView, newView) -> {
            if (oldView == host.normal && newView != host.normal) ++editorFocusLosses;
        };
        decor = host.getWindow().getDecorView();
        // This fresh test-only host has no prior Insets listener. Delegate to
        // the original View behavior and remove only this observer on close.
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            ++observations;
            if (!insets.isVisible(WindowInsets.Type.ime())) ++hiddenObservations;
            else ++visibleObservations;
            return view.onApplyWindowInsets(insets);
        });
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(window);
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener(editor);
    }
    void requestInitialInsets(){decor.requestApplyInsets();}
    int visibilityState() {
        WindowInsets insets = host.normal.getRootWindowInsets();
        return insets == null ? -1 : insets.isVisible(WindowInsets.Type.ime()) ? 1 : 0;
    }
    boolean visible() { return visibilityState() == 1; }
    @Override public void close() {
        decor.setOnApplyWindowInsetsListener(null);
        if (decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnWindowFocusChangeListener(window);
            decor.getViewTreeObserver().removeOnGlobalFocusChangeListener(editor);
        }
    }
}
