// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.a3picker;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Drives the password field and confirmation without putting the synthetic code in ADB arguments. */
public final class RecoveryPickerProbe extends Instrumentation {
    private static final String PRODUCT = "org.zapime.android";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        byte[] code = new byte[43];
        File input = new File(getTargetContext().getFilesDir(), "fixture-recovery-code");
        String stage = "private_code_missing";
        try {
            if (!input.isFile()) throw new IllegalStateException();
            stage = "private_code_read";
            try (DataInputStream source = new DataInputStream(new FileInputStream(input))) {
                source.readFully(code);
                if (source.read() != -1) throw new IllegalStateException();
            } finally {
                if (!input.delete()) {
                    stage = "private_code_delete";
                    throw new IllegalStateException();
                }
            }
            stage = "password_field";
            AccessibilityNodeInfo password = await(null);
            Bundle text = new Bundle();
            text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    new String(code, StandardCharsets.US_ASCII));
            if (!password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) throw new IllegalStateException();
            text.clear();
            stage = "import_button";
            click("验证并导入密钥…");
            stage = "import_dialog";
            click("验证并导入");
            result.putString("recovery_submitted", "PASS");
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
            result.putString("recovery_submitted", "FAIL");
            result.putString("recovery_ui_stage", stage);
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            Arrays.fill(code, (byte) 0);
        }
    }

    private void click(String label) {
        if (!await(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new IllegalStateException();
    }

    private AccessibilityNodeInfo await(String label) {
        long end = SystemClock.elapsedRealtime() + 8_000L;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo found = find(root, label);
            if (found != null) return found;
            SystemClock.sleep(150);
        } while (SystemClock.elapsedRealtime() < end);
        throw new IllegalStateException();
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String label) {
        if (node == null || !PRODUCT.equals(String.valueOf(node.getPackageName()))) return null;
        if (node.isEnabled() && node.isVisibleToUser()) {
            if (label == null ? node.isEditable() && node.isPassword()
                    : node.isClickable() && node.getText() != null && label.contentEquals(node.getText())) return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = find(node.getChild(index), label);
            if (found != null) return found;
        }
        return null;
    }
}
