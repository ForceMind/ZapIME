// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android.voicetest;

import android.app.Instrumentation;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;

/** Same-UID synthetic service-method probe, separate from real system IME/Editable tests. */
final class LearningHostProbe {
    private static Object call(Object owner, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, signature);
        method.setAccessible(true); return method.invoke(owner, args);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    static void run(Instrumentation instrumentation) throws Exception {
        final Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            Object service = null;
            try {
                ClassLoader loader = instrumentation.getTargetContext().getClassLoader();
                Class<?> type = Class.forName("org.zapime.android.ZapInputMethodService", true, loader);
                Class<?> ticketType = Class.forName("org.zapime.android.EnglishLearningSession$Ticket", true, loader);
                service = type.getDeclaredConstructor().newInstance();
                Object session = field(service, "learning");
                Class<?>[] none = new Class<?>[0];
                Class<?>[] commitSignature = {InputConnection.class, String.class, ticketType};
                StringBuilder acceptedText = new StringBuilder();
                boolean[] accept = {true};
                BaseInputConnection editor = new BaseInputConnection(new View(instrumentation.getTargetContext()), true) {
                    @Override public boolean commitText(CharSequence text, int cursor) {
                        if (accept[0]) acceptedText.append(text); return accept[0];
                    }
                    @Override public CharSequence getTextBeforeCursor(int n, int flags) { throw new AssertionError("learning read editor"); }
                    @Override public CharSequence getTextAfterCursor(int n, int flags) { throw new AssertionError("learning read editor"); }
                    @Override public CharSequence getSelectedText(int flags) { throw new AssertionError("learning read selection text"); }
                };
                check(call(session, "capture", none) == null, "production default captured input");
                call(service, "trackCommit", commitSignature, editor, "默认关闭。", null);
                check(call(session, "takeReady", new Class<?>[]{long.class}, Long.MAX_VALUE) == null, "disabled queued work");
                call(session, "setEnabled", new Class<?>[]{boolean.class}, true);
                call(session, "setModelVersion", new Class<?>[]{String.class}, "synthetic-method-probe");
                call(session, "beginEditor", new Class<?>[]{boolean.class}, true);
                Object ticket = call(session, "capture", none);
                check(call(service, "captureLearning", none) == ticket, "ordinary source has no ticket");
                for (String layer : new String[]{"tools", "edit", "data", "voice"}) {
                    set(service, "layer", layer);
                    check(call(service, "captureLearning", none) == null, "excluded panel issued ticket");
                }
                set(service, "layer", "letters"); set(service, "privateSession", true);
                check(call(service, "captureLearning", none) == null, "private source issued ticket");
                set(service, "privateSession", false); set(service, "searchActive", true);
                check(call(service, "captureLearning", none) == null, "search source issued ticket");
                set(service, "searchActive", false);
                Object echoes = field(service, "selectionEcho");
                call(echoes, "reset", new Class<?>[]{int.class, int.class}, 0, 0);
                call(service, "trackCommit", commitSignature, editor, "明天", ticket);
                call(service, "trackCommit", commitSignature, editor, "见。", ticket);
                Class<?>[] selectionSignature = {int.class, int.class, int.class, int.class, int.class, int.class};
                call(service, "updateSelectionState", selectionSignature, 0, 0, 4, 4, -1, -1);
                check(call(session, "capture", none) == ticket, "own selection echo invalidated sentence");
                Object request = call(session, "takeReady", new Class<?>[]{long.class}, Long.MAX_VALUE);
                check(request != null && "明天见。".equals(call(request, "source", none)), "accepted service commits not assembled");
                call(session, "complete", new Class<?>[]{request.getClass(), String.class}, request, null);

                call(service, "trackCommit", commitSignature, editor, "拒绝前", ticket);
                accept[0] = false;
                call(service, "trackCommit", commitSignature, editor, "拒绝内容。", ticket);
                check(call(session, "takeReady", new Class<?>[]{long.class}, Long.MAX_VALUE) == null, "rejected commit retained source");
                check(!acceptedText.toString().contains("拒绝内容"), "rejected fixture wrote text");
                accept[0] = true;
                call(service, "trackCommit", commitSignature, editor, "旧队列。", ticket);
                check(call(session, "takeReady", new Class<?>[]{long.class}, Long.MAX_VALUE) == null, "stale queued provenance accepted");
                ticket = call(session, "capture", none);
                call(service, "trackCommit", commitSignature, editor, "资料前", ticket);
                call(service, "trackCommit", commitSignature, editor, "资料插入。", null);
                check(call(session, "takeReady", new Class<?>[]{long.class}, Long.MAX_VALUE) == null, "excluded write retained source");
                check(acceptedText.toString().contains("资料插入。"), "excluded learning changed editor write");
                ticket = call(session, "capture", none);
                call(service, "trackCommit", commitSignature, editor, "外部改动前", ticket);
                call(service, "updateSelectionState", selectionSignature, 4, 4, 1, 1, -1, -1);
                check(call(session, "capture", none) != ticket
                        && call(session, "takeReady", new Class<?>[]{long.class}, Long.MAX_VALUE) == null,
                        "external selection did not clear sentence");

                Class<?> policy = Class.forName("org.zapime.android.EnglishLearningEligibility", true, loader);
                Method allows = policy.getDeclaredMethod("allows", EditorInfo.class, boolean.class); allows.setAccessible(true);
                EditorInfo info = new EditorInfo(); info.inputType = InputType.TYPE_CLASS_TEXT;
                check((Boolean)allows.invoke(null, info, false), "ordinary text rejected");
                check(!(Boolean)allows.invoke(null, info, true), "private session eligible");
                check(!(Boolean)allows.invoke(null, null, false), "missing editor eligible");
                for (int variation : new int[]{InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE}) {
                    info.inputType = InputType.TYPE_CLASS_TEXT | variation | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
                    check((Boolean)allows.invoke(null, info, false), "allowed text flags rejected");
                }
                for (int variation : new int[]{InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT}) {
                    info.inputType = InputType.TYPE_CLASS_TEXT | variation;
                    check(!(Boolean)allows.invoke(null, info, false), "protected/unknown variation eligible");
                }
                for (int inputType : new int[]{InputType.TYPE_NULL, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS}) {
                    info.inputType = inputType;
                    check(!(Boolean)allows.invoke(null, info, false), "protected field eligible");
                }
                info.inputType = InputType.TYPE_CLASS_TEXT; info.imeOptions = EditorInfo.IME_ACTION_SEARCH;
                check(!(Boolean)allows.invoke(null, info, false), "declared search eligible");
                info.imeOptions = EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
                check(!(Boolean)allows.invoke(null, info, false), "no-learning field eligible");
            } catch (Throwable error) { failure[0] = error; }
            finally {
                // This fixture was never attached/activated as a system Service.
                // Do not invoke its real lifecycle or touch the real active IME.
                if (service != null) for (String name : new String[]{"worker", "reader"}) {
                    try { ((ExecutorService)field(service, name)).shutdownNow(); }
                    catch (Throwable error) { if (failure[0] == null) failure[0] = error; }
                }
            }
        });
        if (failure[0] != null) throw new Exception("synthetic learning service-method probe failed", failure[0]);
    }
    private LearningHostProbe() {}
}
