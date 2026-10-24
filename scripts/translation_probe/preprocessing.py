#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Audited developer probe adapter for the archive's PRE=simple pipeline."""
import hashlib
import re
import shutil
import subprocess

from prepare_preprocessing import verify_helpers

ARCHIVE_SCRIPT_HASHES = {
    "preprocess.sh": "6383c597263f4e7249bda87de1c06791a7d0a140f73c05ebc3ea2dddeb796ffd",
    "postprocess.sh": "039cec87f6d037fa216e918f3b0a7d4fd46517e54f4d422e73e0b4d6fc27ac0d",
    "decoder.yml": "aec96eb0d7bb8fe173121f3e53bc50306bd6233cb008cac9aab4f561fed43057",
}


class OpusSimple:
    def __init__(self, root):
        self.root = root
        self.files = verify_helpers(root)
        for name, digest in ARCHIVE_SCRIPT_HASHES.items():
            with (root / "raw" / name).open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != digest:
                    raise RuntimeError("model archive pipeline differs from reviewed source")
        self.perl = shutil.which("perl")
        if self.perl is None:
            raise RuntimeError("Perl unavailable; no preprocessing-equivalence claim")
        # Do not inherit PERL5OPT/PERL5LIB/PERL_UNICODE or execute the archive shell.
        self.env = {"PATH": "/usr/bin:/bin", "LC_ALL": "C.UTF-8"}
        self.perl_version = subprocess.run(
            [self.perl, "-e", "print $^V"], capture_output=True, check=True,
            timeout=5, env=self.env, text=True,
        ).stdout

    def __call__(self, text):
        if "\n" in text or "\r" in text:
            raise ValueError("probe adapter accepts one synthetic line at a time")
        data = (text + "\n").encode("utf-8")
        for name in ("replace-unicode-punctuation.perl", "remove-non-printing-char.perl"):
            result = subprocess.run(
                [self.perl, str(self.root / "moses" / name)], input=data,
                capture_output=True, check=True, timeout=5, env=self.env,
            )
            if result.stderr:
                raise RuntimeError("reviewed preprocessing helper reported a warning")
            data = result.stdout
        value = data.decode("utf-8", errors="strict")
        if not value.endswith("\n") or "\n" in value[:-1]:
            raise RuntimeError("preprocessing unexpectedly changed line count")
        # Exact ASCII-space collapse/trim in the archived sed expression.
        return re.sub(" +", " ", value[:-1]).strip(" ")

    def description(self):
        return {
            "method": "reviewed archive PRE=simple: replace-unicode-punctuation.perl, remove-non-printing-char.perl, ASCII-space collapse/trim, bundled SentencePiece",
            "normalize_punctuation_perl": "not used: commented out in this model's preprocess.sh",
            "archive_scripts_sha256": ARCHIVE_SCRIPT_HASHES,
            "helpers": self.files,
            "perl": self.perl,
            "perl_version": self.perl_version,
            "execution": "two fixed reviewed Perl helpers, no archive shell, no helper download during inference",
        }


def archive_postprocess(tokens):
    """The archive sed retains its dummy-prefix leading space; do not hide it."""
    return "".join(tokens).replace(" ", "").replace("▁", " ")
