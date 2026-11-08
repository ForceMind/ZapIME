# SPDX-License-Identifier: GPL-3.0-only
"""Strict same-editor proof; interruption is a failure, not a reproduced bug."""


def verify_configuration_report(report, token, api):
    if (not isinstance(report, dict) or type(report.get("schema_version")) is not int
            or report["schema_version"] != 2 or api != 35
            or report.get("api_level") != api or report.get("run_attempt") != token
            or report.get("scope") != "production-system-ime-configuration-restart"):
        raise ValueError("Wrong configuration experiment identity")
    if report.get("classification") != "same_editor_composition_preserved":
        raise ValueError("Configuration experiment interrupted or composition failed")
    for field in ("passed", "synthetic", "setting_restored", "same_editor_continuity",
                  "framework_restart_observed", "service_trace_complete", "before_composing",
                  "after_i_composing", "candidate_touch", "context_seed_touch",
                  "observer_initial_visible_confirmed", "fresh_verification_completed"):
        if report.get(field) is not True:
            raise ValueError("Missing actual configuration proof: " + field)
    for field in ("input_finished_after_before", "editor_composing", "product_preferences_written",
                  "human_touch_or_phone_latency", "model_execution_claimed", "empty_initialization_finish"):
        if report.get(field) is not False:
            raise ValueError("Unexpected configuration experiment claim: " + field)
    for field in ("visibility_hidden_observations", "window_focus_losses", "editor_focus_losses"):
        if type(report.get(field)) is not int or report[field] != 0:
            raise ValueError("Observed actual input interruption")
    for before, after in (("before_connection_count", "after_connection_count"),
                          ("before_command_connection", "after_command_connection")):
        if (type(report.get(before)) is not int or report[before] < 1
                or type(report.get(after)) is not int or report[before] != report[after]):
            raise ValueError("Independent input connection changed")
    before, after = report.get("before_restarts"), report.get("after_restarts")
    if type(before) is not int or type(after) is not int or not 0 <= before < after:
        raise ValueError("No observed framework restart")
    setting = report.get("before_setting")
    if (type(setting) is not int or setting not in (0, 1)
            or type(report.get("inverse_readback")) is not int
            or report["inverse_readback"] != 1 - setting
            or type(report.get("trigger_restored_readback")) is not int
            or report["trigger_restored_readback"] != setting):
        raise ValueError("System setting transition not read back")
    if (type(report.get("observer_initial_observations")) is not int or report["observer_initial_observations"] < 1
            or type(report.get("insets_observations")) is not int
            or report["insets_observations"] < report["observer_initial_observations"]
            or report.get("touch_keypresses") != 8 or type(report.get("touch_keypresses")) is not int
            or report.get("context_seed_commits") != 1 or type(report.get("context_seed_commits")) is not int):
        raise ValueError("Incomplete physical-input or visibility observation")
    seed, selected = report.get("context_seed_text"), report.get("selected_text")
    if (not isinstance(seed, str) or len(seed) != 2 or not isinstance(selected, str) or not selected
            or not all(0x3400 <= ord(c) <= 0x9fff for c in seed + selected)
            or report.get("before_text") != seed + "sh" or report.get("after_i_text") != seed + "shi"
            or report.get("actual_editable") != seed + selected):
        raise ValueError("Real Editable/candidate sequence is not exact")
