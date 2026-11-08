#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Separate API35 experiment using the existing strict disposable-IME wrapper."""
from android_candidate_ime_smoke import main

if __name__ == "__main__":
    main(configuration_restart=True)
