// SPDX-License-Identifier: GPL-3.0-only
import org.zapime.android.EnglishLearningGate;
import org.zapime.android.EnglishLearningGate.Pair;
import org.zapime.android.EnglishLearningGate.Request;

/** Synthetic lifecycle tests only; this does not run or assess a translator. */
public final class EnglishLearningGateSmoke {
    private static void check(boolean ok, String phase) {
        if (!ok) throw new AssertionError(phase);
    }
    private static EnglishLearningGate ready() {
        EnglishLearningGate gate = new EnglishLearningGate();
        gate.setEnabled(true); gate.setModelVersion("synthetic-local-v1"); gate.beginEditor(true);
        return gate;
    }
    private static Request offer(EnglishLearningGate gate, String source) {
        check(gate.offerCommittedSnippet(source, 1000), "offer");
        Request request = gate.takeReady(1600);
        check(request != null, "ready");
        return request;
    }
    private static String repeated(char c, int count) {
        char[] value = new char[count]; java.util.Arrays.fill(value, c); return new String(value);
    }
    public static void main(String[] args) {
        EnglishLearningGate closed = new EnglishLearningGate();
        closed.beginEditor(true); closed.setModelVersion("synthetic-local-v1");
        check(!closed.offerCommittedSnippet("明天见", 1000), "disabled by default");
        closed.setEnabled(true); closed.setModelVersion(null);
        check(!closed.offerCommittedSnippet("明天见", 1000), "missing model");
        closed.setModelVersion("synthetic-local-v1"); closed.beginEditor(false);
        check(!closed.offerCommittedSnippet("明天见", 1000), "protected or ineligible editor");
        check(closed.takeReady(Long.MAX_VALUE) == null, "no protected request");

        EnglishLearningGate gate = ready();
        check(gate.offerCommittedSnippet("明天见", 1000), "normal commit snapshot");
        check(gate.takeReady(1599) == null, "pause not elapsed");
        Request first = gate.takeReady(1600);
        check(first != null && first.source().equals("明天见"), "exact submitted source");
        check(gate.takeReady(9999) == null, "no duplicate dispatch");
        check(gate.complete(first, "See you tomorrow."), "synthetic result accepted");
        Pair pair = gate.visiblePair();
        check(gate.isCurrent(pair) && pair.source().equals("明天见")
                && pair.english().equals("See you tomorrow."), "immutable pair for explicit collect");
        check(!first.toString().contains("明天") && !pair.toString().contains("tomorrow"), "redacted diagnostics");
        gate.beginEditor(true); // Even the same editor's restart must invalidate.
        check(gate.visiblePair() == null && !gate.isCurrent(pair), "restart rejects old collect click");

        Request running = offer(gate, "第一句");
        for (int i = 0; i < 100; ++i) {
            check(gate.offerCommittedSnippet("待译" + i, 2000 + i), "coalesced latest");
            check(gate.takeReady(9999) == null, "running worker retains slot");
        }
        check(!gate.complete(running, "old synthetic result"), "older revision rejected");
        Request latest = gate.takeReady(9999);
        check(latest != null && latest.source().equals("待译99"), "only newest pending survives");
        check(!gate.complete(running, "duplicate"), "duplicate callback rejected");
        gate.offerCommittedSnippet("再下一句", 10000);
        check(gate.takeReady(99999) == null, "duplicate did not release another job's slot");
        check(!gate.complete(latest, null), "cancel releases owned slot without output");
        Request afterCancel = gate.takeReady(99999);
        check(afterCancel != null && afterCancel.source().equals("再下一句"), "latest after cancel");
        gate.beginEditor(false);
        check(!gate.complete(afterCancel, "must not appear"), "focus/private transition rejects completion");
        check(gate.visiblePair() == null, "no private preview");

        gate.beginEditor(true);
        Request oldModel = offer(gate, "模型切换");
        gate.setModelVersion("synthetic-local-v2");
        check(!gate.complete(oldModel, "old model result"), "model version binding");
        Request newer = offer(gate, "新的片段");
        check(newer.modelVersion().equals("synthetic-local-v2"), "verified model identity snapshot");
        gate.setEnabled(false);
        check(!gate.complete(newer, "disabled result"), "disable rejects callback");
        gate.setEnabled(true);
        Request invalidated = offer(gate, "删除前片段");
        gate.invalidate();
        check(!gate.complete(invalidated, "obsolete"), "external edit/hide/cancel invalidates");

        check(gate.offerCommittedSnippet("明天见。", 20000), "sentence end");
        Request boundary = gate.takeReady(20000);
        check(boundary != null, "sentence boundary dispatch without pause");
        check(!gate.complete(boundary, "\ud800"), "malformed target rejected");
        Request oversized = offer(gate, "短句");
        check(!gate.complete(oversized, repeated('a', EnglishLearningGate.MAX_TARGET_UNITS + 1)), "target bound");
        for (String invalid : new String[]{"\u200b", "\u200d", "\ufe0f", "\u0301", "\u202eEnglish", "English\u2066", "\u200eEnglish", "\u061cEnglish"}) {
            Request hidden = offer(gate, "合成短句");
            check(!gate.complete(hidden, invalid) && gate.visiblePair() == null, "invisible-only or bidi control rejected");
        }
        Request unicode = offer(gate, "一家人");
        check(gate.complete(unicode, "Family 👨‍👩‍👧‍👦 café"), "legitimate joiners and accents preserved");
        check(!gate.offerCommittedSnippet(repeated('中', EnglishLearningGate.MAX_SOURCE_UNITS + 1), 0), "source bound");
        check(!gate.offerCommittedSnippet("中文\ud800", 0), "malformed source rejected");
        check(!gate.offerCommittedSnippet("中文\u0000", 0), "control character rejected");
        check(!gate.offerCommittedSnippet("   ", 0), "blank rejected");
        check(!gate.offerCommittedSnippet("English only", 0), "Chinese-source scope");
        check(!gate.offerCommittedSnippet("中文", -1), "invalid clock rejected");
        check(gate.takeReady(Long.MAX_VALUE) == null, "invalid source leaves no pending item");
        check(gate.offerCommittedSnippet("中文😀", Long.MAX_VALUE - 1), "valid surrogate pair and saturated clock");
        check(gate.takeReady(Long.MAX_VALUE - 1) == null, "deadline must not overflow");
        Request bounded = gate.takeReady(Long.MAX_VALUE);
        check(bounded != null && gate.complete(bounded, "synthetic fixture"), "bounded completion");
        Pair shown = gate.visiblePair();
        check(!gate.offerCommittedSnippet(repeated('中', 257), 1000)
                && !gate.isCurrent(shown) && gate.visiblePair() == null, "invalid new source clears stale preview");
        System.out.println("PASS English learning lifecycle: opt-in/privacy/model gates, debounce, bounded latest-only scheduling, immutable pairs and stale-result rejection (synthetic; no translator or host integration)");
    }
}
