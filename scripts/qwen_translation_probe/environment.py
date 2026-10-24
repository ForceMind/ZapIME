#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Reject hidden native configuration and pass a small explicit environment."""
from pathlib import Path

from assets import ROOT


def controlled_environment():
    config = ROOT / "empty-xdg-config"
    config.mkdir(exist_ok=True)
    candidates = [Path("/etc/llama.cpp/config.ini"), config / "llama.cpp/config.ini"]
    if any(path.exists() for path in candidates):
        raise RuntimeError("external llama.cpp configuration would change the experiment")
    # Do not inherit LLAMA_ARG_*, proxies, LD_PRELOAD or user-config paths.
    environment = {"PATH": "/usr/bin:/bin", "LC_ALL": "C.UTF-8", "XDG_CONFIG_HOME": str(config), "OMP_NUM_THREADS": "1", "OMP_THREAD_LIMIT": "1", "OPENBLAS_NUM_THREADS": "1"}
    return environment, [str(path) for path in candidates]
