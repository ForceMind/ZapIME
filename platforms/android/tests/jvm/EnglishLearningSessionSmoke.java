// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

/** Synthetic provenance/assembly regression, not translation quality or editor acceptance. */
public final class EnglishLearningSessionSmoke {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static EnglishLearningSession ready() {
        EnglishLearningSession session = new EnglishLearningSession();
        session.setEnabled(true); session.setModelVersion("synthetic-v1"); session.beginEditor(true);
        return session;
    }
    private static void commit(EnglishLearningSession session, String text, long time) {
        session.committed(session.capture(), text, true, time);
    }
    private static EnglishLearningGate.Request expect(EnglishLearningSession session, String text, long time) {
        EnglishLearningGate.Request request = session.takeReady(time);
        check(request != null && text.equals(request.source()), "assembled source mismatch");
        return request;
    }
    public static void main(String[] args) {
        EnglishLearningSession off = new EnglishLearningSession();
        off.beginEditor(true); off.setModelVersion("synthetic-v1");
        check(off.capture() == null, "disabled ingestion");
        off.committed(null, "未启用时的合成文本", true, 1000);
        off.setEnabled(true); check(off.takeReady(9999) == null, "no catch-up input history");
        off.setModelVersion(null); check(off.capture() == null, "no-model ingestion");
        off.setModelVersion("synthetic-v1"); off.beginEditor(false);
        check(off.capture() == null, "private ingestion");

        EnglishLearningSession session = ready();
        EnglishLearningSession.Ticket queued = session.capture();
        session.committed(queued, "我", true, 1000);
        session.committed(queued, "正在", true, 1100);
        session.committed(queued, "学习英语", true, 1200);
        check(session.takeReady(1799) == null, "debounce combined accepted commits");
        session.committed(queued, "。", true, 1300);
        EnglishLearningGate.Request first = expect(session, "我正在学习英语。", 1300);
        check(session.complete(first, "Synthetic English one."), "complete current");
        EnglishLearningGate.Pair shown = session.visiblePair();
        check(session.isCurrent(shown), "current immutable pair");
        commit(session, "明天见", 1400); // A fresh sentence after completed punctuation.
        check(!session.isCurrent(shown), "old pair no longer collectible");
        EnglishLearningGate.Request second = expect(session, "明天见", 2000);
        session.beginEditor(true); // Retained composition restart still changes learning context.
        check(session.capture() != queued, "independent restart token");
        commit(session, "新会话", 2100);
        session.committed(queued, "旧队列回报", true, 2200);
        check(!session.complete(second, "Stale synthetic result."), "restart stale completion");
        EnglishLearningGate.Request next = expect(session, "新会话", 2700);
        session.complete(next, null);

        session = ready(); queued = session.capture();
        commit(session, "拒绝前", 1000);
        session.committed(queued, "未接受", false, 1100);
        check(session.capture() != queued && session.takeReady(9999) == null, "rejected commit clears context");
        session.committed(queued, "迟到成功", true, 1200);
        check(session.takeReady(9999) == null, "rejection also invalidates already queued provenance");
        commit(session, "新输入", 2000);
        session.committed(null, "资料插入", true, 2100);
        next = expect(session, "新输入", 2600); // Host clears excluded writes explicitly, null token itself is inert.
        session.complete(next, null);
        session.invalidate(); commit(session, "清空后", 3000);
        queued = session.capture(); session.suspend();
        check(session.capture() == null, "hidden ingestion suspended");
        session.committed(queued, "隐藏后旧队列", true, 3100);
        session.beginEditor(true); check(session.takeReady(9999) == null, "show does not revive hidden text");

        session = ready(); queued = session.capture();
        for (int i = 0; i < 256; i++) session.committed(queued, "字", true, 1000 + i);
        session.committed(queued, "超限", true, 1300);
        session.committed(queued, "尾部", true, 1400);
        check(session.takeReady(9999) == null, "no misleading tail after overflow");
        session.committed(queued, "。", true, 1500);
        session.committed(queued, "下一句", true, 1600);
        next = expect(session, "下一句", 2200); session.complete(next, null);
        String oversized = new String(new char[257]).replace('\0', '长');
        session.committed(queued, oversized, true, 2300);
        session.committed(queued, "不保留尾部", true, 2400);
        check(session.takeReady(9999) == null, "oversized individual fragment discarded");
        session.committed(queued, "停顿后", true, 2400 + EnglishLearningSession.CONTEXT_GAP_MS + 1);
        next = expect(session, "停顿后", 2400 + EnglishLearningSession.CONTEXT_GAP_MS + 601);
        session.complete(next, null);

        session = ready(); commit(session, "组合❤️", 1000); commit(session, " ", 1100);
        next = expect(session, "组合❤️ ", 1700); session.complete(next, null);
        queued = session.capture(); session.committed(queued, "\u202e", true, 1800);
        check(session.takeReady(9999) == null && session.capture() != queued, "bidi fragment clears source");
        commit(session, "合成", 2000); commit(session, "\ud800", 2100);
        check(session.takeReady(9999) == null, "malformed fragment clears source");
        commit(session, "合成", 3000); commit(session, "倒退时钟", 2999);
        check(session.takeReady(9999) == null, "clock reversal clears source");
        commit(session, "旧模型", 4000); session.setModelVersion("synthetic-v2");
        check(session.takeReady(9999) == null, "model change clears assembly");
        commit(session, "再输入", 5000); session.setEnabled(false); session.setEnabled(true);
        check(session.takeReady(9999) == null, "enable cycle has no retained history");
        check(!session.toString().contains("再输入") && !session.capture().toString().contains("再输入"), "redacted objects");
        System.out.println("PASS English learning accepted-commit assembly, provenance, bounds and lifecycle (synthetic only)");
    }
}
