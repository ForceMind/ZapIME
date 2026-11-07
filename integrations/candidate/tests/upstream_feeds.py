# SPDX-License-Identifier: GPL-3.0-only
# Copyright 2026 chinese-ime-lm contributors
# Verbatim feed builders from neural/choice/model.py, commit
# 5b82758eaf3419dff9555f41bbda2744a56e45c4 (rerank-v2).
# Git blob: 933af7444124970a221b3bf1f10a81599c9170d1.
# Only this provenance header was added. See ../SOURCE.md.


def step_inputs(n_context, bodies, cached):
    """The inputs of `lm_step` for one keystroke: n_context the context nodes at the head of the cache (BOS and the committed text, see `context_inputs`), bodies the candidates as lists of character ids, cached the candidate prefixes (tuples) already in the cache after the context, in cache order. Returns (nodes, positions, visible, steps, cached) as Python lists, cached extended with the new prefixes for the next keystroke; nodes may be empty, when every prefix is cached."""
    index = {p: n_context + i for i, p in enumerate(cached)}
    fresh = []
    for body in bodies:
        for t in range(1, len(body)):
            prefix = tuple(body[:t])
            if prefix not in index:
                index[prefix] = n_context + len(cached) + len(fresh)
                fresh.append(prefix)
    every = list(cached) + fresh
    nodes = [p[-1] for p in fresh]
    positions = [n_context + len(p) - 1 for p in fresh]
    visible = [[True] * n_context + [len(q) <= len(p) and p[: len(q)] == q for q in every] for p in fresh]
    width = max(map(len, bodies))
    steps = [
        [(n_context - 1 if t == 0 else index[tuple(body[:t])]) if t < len(body) else 0 for t in range(width)]
        for body in bodies
    ]
    return nodes, positions, visible, steps, every


def context_inputs(context):
    """The inputs of `lm_step` that encode a committed context (character ids opened with BOS) into an empty cache: (nodes, positions, visible, candidates, steps, lengths), the context as a chain of nodes each seeing those before it, and one placeholder candidate whose score is ignored."""
    n = len(context)
    return list(context), list(range(n)), [[j <= i for j in range(n)] for i in range(n)], [[context[0]]], [[0]], [1]
