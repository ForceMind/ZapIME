# SPDX-License-Identifier: GPL-3.0-only
"""Bounded Unicode-codepoint CER; neither a word metric nor an accuracy claim."""
import unicodedata

MAX_CODEPOINTS = 2048
PROFILES = ("verbatim", "speech-v1")


def normalize(text, profile):
    if profile not in PROFILES or not isinstance(text, str):
        raise ValueError("invalid metric profile or text type")
    if len(text) > MAX_CODEPOINTS or any(unicodedata.category(c) in ("Cc", "Cf", "Cs") for c in text):
        raise ValueError("metric input exceeds bound or contains control/surrogate characters")
    if profile == "verbatim":
        return text
    text = unicodedata.normalize("NFKC", text).lower()
    text = "".join(c for c in text if not c.isspace() and not unicodedata.category(c).startswith("P"))
    if len(text) > MAX_CODEPOINTS:
        raise ValueError("normalized metric input exceeds bound")
    return text


def counts(reference, hypothesis, profile="speech-v1"):
    reference, hypothesis = normalize(reference, profile), normalize(hypothesis, profile)
    # Cell: (edit distance, substitutions, deletions, insertions). Rolling rows
    # keep memory bounded. Equal-distance ties prefer substitution, deletion,
    # insertion in that order; counts may differ from other aligners at ties.
    previous = [(j, 0, 0, j) for j in range(len(hypothesis)+1)]
    for i, expected in enumerate(reference, 1):
        current = [(i, 0, i, 0)]
        for j, actual in enumerate(hypothesis, 1):
            diagonal = previous[j-1]
            if expected == actual:
                current.append(diagonal)
                continue
            deletion, insertion = previous[j], current[j-1]
            choices = ((diagonal[0]+1, diagonal[1]+1, diagonal[2], diagonal[3]),
                       (deletion[0]+1, deletion[1], deletion[2]+1, deletion[3]),
                       (insertion[0]+1, insertion[1], insertion[2], insertion[3]+1))
            current.append(min(choices, key=lambda cell: cell[0]))
        previous = current
    _, substitutions, deletions, insertions = previous[-1]
    return measurement(len(reference), substitutions, deletions, insertions)


def measurement(reference_characters, substitutions, deletions, insertions):
    errors = substitutions+deletions+insertions
    return {"reference_characters": reference_characters, "substitutions": substitutions,
            "deletions": deletions, "insertions": insertions, "errors": errors,
            "cer": errors/reference_characters if reference_characters else None}


def aggregate(rows):
    # Micro-average by total reference characters, never mean(sentence CER).
    rows = list(rows)
    return measurement(*(sum(row[key] for row in rows) for key in
                         ("reference_characters", "substitutions", "deletions", "insertions")))
