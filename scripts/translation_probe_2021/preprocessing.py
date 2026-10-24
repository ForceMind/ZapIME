#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Express the reviewed 2021 archive's actual sed/Perl behavior; no archive shell."""
import re
import subprocess
from pathlib import Path

from assets import FILES, ROOT, verify_file

# Order is immaterial for these non-overlapping substitutions. Full stops are
# handled separately because their archived sed expressions also consume spaces.
# Preserve the upstream fullwidth-one-to-quote mapping instead of hiding its bug.
REPLACEMENTS = {
    "，": ",", "、": ",", "”": '"', "“": '"', "∶": ":", "：": ":", "？": "?",
    "《": '"', "》": '"', "）": ")", "！": "!", "（": "(", "；": ";", "１": '"',
    "」": '"', "「": '"', "０": "0", "３": "3", "２": "2", "５": "5", "６": "6",
    "９": "9", "７": "7", "８": "8", "４": "4", "～": "~", "’": "'", "…": "...",
    "━": "-", "〈": "<", "〉": ">", "【": "[", "】": "]", "％": "%",
}


class ArchivePreprocessing:
    def __init__(self):
        for name in ("preprocess.sh", "postprocess.sh", "decoder.yml"):
            verify_file(ROOT / "raw" / name, *FILES[name])
        self.perl = Path("/usr/bin/perl")
        if not self.perl.is_file():
            raise RuntimeError("fixed Perl path unavailable; no normalization claim")
        self.env = {"PATH": "/usr/bin:/bin", "LC_ALL": "C.UTF-8"}
        self.perl_version = subprocess.run([str(self.perl), "-e", "print $^V"], env=self.env,
                                         capture_output=True, check=True, text=True, timeout=5).stdout

    def __call__(self, text):
        if not isinstance(text, str) or len(text) > 4096 or "\n" in text or "\r" in text:
            raise ValueError("only one bounded synthetic line is accepted")
        text = re.sub("。 *", ". ", text)
        text = text.translate(str.maketrans(REPLACEMENTS))
        text = re.sub("． *", ". ", text)
        # This fixed literal program is the reviewed category-C step, not code
        # loaded from the model ZIP. In this older archive it also replaces the
        # appended newline. No PERL5OPT/PERL5LIB/Unicode environment is inherited.
        result = subprocess.run([str(self.perl), "-C", "-pe", r"s/\p{C}/ /g;"],
                                input=(text + "\n").encode("utf-8"), capture_output=True,
                                check=True, env=self.env, timeout=5)
        if result.stderr:
            raise RuntimeError("fixed normalization program reported a warning")
        return re.sub(" +", " ", result.stdout.decode("utf-8", errors="strict")).strip(" ")

    def description(self):
        return {"method": "reviewed 2021 embedded sed substitutions, fixed Perl Unicode-category-C replacement, ASCII-space collapse/trim, bundled SPM",
                "archive_shell_executed": False, "perl": str(self.perl), "perl_version": self.perl_version,
                "known_archive_behavior": ["fullwidth １ becomes a quote", "category-C normalization replaces valid emoji ZWJ too", "one synthetic line per subprocess; original newline is also replaced"]}
