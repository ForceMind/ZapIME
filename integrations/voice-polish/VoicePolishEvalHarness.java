// SPDX-License-Identifier: GPL-3.0-only
package org.zapime.android;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** CI/local corpus helper. Never prints source or generated text. */
public final class VoicePolishEvalHarness {
    private VoicePolishEvalHarness() {}
    public static void main(String[] arguments) throws Exception {
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String row;
            while ((row = input.readLine()) != null) {
                String[] pieces = row.split("\\t", -1);
                if (pieces.length != 2) throw new IllegalArgumentException("invalid_eval_pair");
                String raw = new String(Base64.getDecoder().decode(pieces[0]), StandardCharsets.UTF_8);
                String proposed = new String(Base64.getDecoder().decode(pieces[1]), StandardCharsets.UTF_8);
                VoicePolishSafety.Reason code = VoicePolishSafety.inspect(raw, proposed);
                boolean returned = VoicePolishSafety.safePreview(raw, proposed).equals(proposed);
                System.out.println(code.name() + "\t" + (returned ? "1" : "0"));
            }
        }
    }
}
