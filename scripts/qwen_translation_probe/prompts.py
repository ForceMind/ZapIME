#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Only the two-message, no-tool branch of the pinned official Qwen template."""
import hashlib
import json

from assets import ROOT, verify_asset

SYSTEM_PROMPTS = {
    "english": "Translate the Chinese text into English. Output only the translation, without explanations. Preserve negation and intent. /no_think",
    "chinese": "你是一名专业的中英翻译。请把用户输入的中文翻译成自然、准确的英文，保留原意和否定关系。只输出译文，不要解释。/no_think",
}
ASSISTANT_PREFIX = "<|im_start|>assistant\n<think>\n\n</think>\n\n"


def render_prompt(system, source):
    # Rendering the fixed official branch, not claiming a runtime API flag exists.
    return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + source + "<|im_end|>\n" + ASSISTANT_PREFIX


def template_record():
    asset = verify_asset("tokenizer_config.json")
    template = json.loads((ROOT / "tokenizer_config.json").read_text())["chat_template"]
    return {
        "official_template": template,
        "official_tokenizer_config_asset": asset,
        "template_sha256": hashlib.sha256(template.encode("utf-8")).hexdigest(),
        "rendering_scope": "manual exact two-message/no-tool/add_generation_prompt=True/enable_thinking=False branch; no general Jinja engine or tokenizer apply_chat_template API invoked",
        "assistant_prefix": ASSISTANT_PREFIX,
        "system_prompts": SYSTEM_PROMPTS,
        "no_model_thinking_text_removed_from_output": True,
    }
