// SPDX-License-Identifier: GPL-3.0-only
use serde_json::{json, Value};
use zapime_core::InputSession;

fn fixture(private: bool) -> (tempfile::TempDir, InputSession) {
    fixture_with_rows(private, "")
}

fn fixture_with_rows(private: bool, extra_rows: &str) -> (tempfile::TempDir, InputSession) {
    let dir = tempfile::tempdir().unwrap();
    let resources = dir.path().join("resources");
    std::fs::create_dir(&resources).unwrap();
    let db = rusqlite::Connection::open(resources.join("msime-pinyin.db")).unwrap();
    db.execute_batch(
        "CREATE TABLE tbl_1_n(key TEXT,jp TEXT,value TEXT,weight INTEGER);
         INSERT INTO tbl_1_n VALUES('ni','n','你',900);
         CREATE TABLE tbl_1_h(key TEXT,jp TEXT,value TEXT,weight INTEGER);
         INSERT INTO tbl_1_h VALUES('hao','h','好',900);
         CREATE TABLE tbl_2_n(key TEXT,jp TEXT,value TEXT,weight INTEGER);
         INSERT INTO tbl_2_n VALUES('ni''hao','nh','你好',300);
         INSERT INTO tbl_2_n VALUES('ni''hao','nh','拟好',200);
         INSERT INTO tbl_2_n VALUES('ni''hao','nh','逆号',100);",
    )
    .unwrap();
    db.execute_batch(extra_rows).unwrap();
    msime_engine::ensure_english_schema(&resources.join("msime-english.db")).unwrap();
    let session = InputSession::open(
        &resources,
        &dir.path().join("data"),
        &dir.path().join("cache"),
        private,
    )
    .unwrap();
    (dir, session)
}

fn type_code(session: &mut InputSession, code: &str) -> Value {
    let mut view = json!(null);
    for c in code.chars() {
        view = session.request(&json!({"op":"key","text":c.to_string()}));
    }
    view
}

fn reverse_request(view: &Value) -> Value {
    let ids: Vec<_> = view["ranking"]["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .rev()
        .map(|row| row["id"].clone())
        .collect();
    json!({"op":"candidate_rank","generation":view["generation"],"model":view["ranking"]["model"],"ids":ids})
}

#[test]
fn enabled_by_default_and_reordered_id_commits_original_engine_row() {
    let (_dir, mut session) = fixture(false);
    let original = type_code(&mut session, "nihao");
    assert_eq!(original["ranking"]["enabled"], true);
    assert!(original["ranking"]["candidates"].as_array().unwrap().len() >= 2);
    let target = original["ranking"]["candidates"]
        .as_array()
        .unwrap()
        .last()
        .unwrap()
        .clone();
    let ranked = session.request(&reverse_request(&original));
    assert!(ranked.get("error").is_none(), "{ranked}");
    assert!(ranked["commit"].is_null());
    assert_eq!(ranked["commit_source"], "none");
    assert_eq!(ranked["ranking"]["applied"], true);
    assert_eq!(ranked["candidates"][0], target["text"]);
    assert_eq!(ranked["candidate_ids"][0], target["id"]);
    assert_ne!(ranked["generation"], original["generation"]);
    let stale =
        session.request(&json!({"op":"select","index":0,"generation":original["generation"]}));
    assert_eq!(stale["error"], "stale_generation");
    let commit = session.request(
        &json!({"op":"select","index":0,"id":target["id"],"generation":ranked["generation"]}),
    );
    assert_eq!(commit["commit"], target["text"]);
    assert_eq!(commit["commit_source"], "engine");
    assert_eq!(commit["preedit"], "");
    assert_eq!(commit["ranking"]["applied"], false);
}

#[test]
fn stale_duplicate_foreign_and_text_injection_results_are_rejected_atomically() {
    let (_dir, mut session) = fixture(false);
    let original = type_code(&mut session, "nihao");
    let valid = reverse_request(&original);
    for bad in [
        json!({"op":"candidate_rank","generation":0,"model":valid["model"],"ids":valid["ids"]}),
        json!({"op":"candidate_rank","generation":original["generation"],"model":"other","ids":valid["ids"]}),
        json!({"op":"candidate_rank","generation":original["generation"],"model":valid["model"],"ids":["engine:0","engine:0","engine:0"]}),
        json!({"op":"candidate_rank","generation":original["generation"],"model":valid["model"],"ids":["新增自由生成文字"]}),
    ] {
        assert!(session.request(&bad).get("error").is_some());
        assert_eq!(session.request(&json!({"op":"view"})), original);
    }
    session.request(&json!({"op":"command","name":"Backspace"}));
    assert_eq!(session.request(&valid)["error"], "stale_generation");
}

#[test]
fn private_session_cannot_enable_or_export_ranking_pool() {
    let (_dir, mut session) = fixture(true);
    let view = type_code(&mut session, "nihao");
    assert_eq!(view["ranking"]["enabled"], false);
    assert!(view["ranking"]["candidates"].as_array().unwrap().is_empty());
    let enabled = session.request(&json!({"op":"candidate_ranking","enabled":true}));
    assert_eq!(enabled["ranking"]["enabled"], false);
    let result = session.request(&reverse_request(&enabled));
    assert_eq!(result["error"], "candidate_ranking_disabled");
}

#[test]
fn disabling_restores_baseline_and_prevents_late_application() {
    let (_dir, mut session) = fixture(false);
    let original = type_code(&mut session, "nihao");
    let ranked = session.request(&reverse_request(&original));
    let disabled = session.request(&json!({"op":"candidate_ranking","enabled":false}));
    assert_eq!(disabled["candidates"], original["candidates"]);
    assert_eq!(disabled["ranking"]["enabled"], false);
    assert!(session
        .request(&reverse_request(&ranked))
        .get("error")
        .is_some());
    assert_eq!(session.request(&json!({"op":"view"})), disabled);
}

#[test]
fn personal_rule_remains_first_and_keeps_saved_provenance() {
    let (_dir, mut session) = fixture(false);
    let edit = json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"snippet","code":"nihao","text":"合成指定短语","tags":[]}});
    assert!(session.request(&edit).get("error").is_none());
    let original = type_code(&mut session, "nihao");
    let ranked = session.request(&reverse_request(&original));
    assert_eq!(ranked["candidates"][0], "合成指定短语");
    let commit = session.request(&json!({"op":"command","name":"CommitCandidate"}));
    assert_eq!(commit["commit"], "合成指定短语");
    assert_eq!(commit["commit_source"], "saved_data");
}

#[test]
fn partial_candidate_slot_and_remaining_composition_survive_reordering() {
    let (_dir, mut session) = fixture(false);
    let original = type_code(&mut session, "nihao");
    let partial = original["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|word| word == "你")
        .unwrap();
    assert!(!original["ranking"]["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .any(|row| row["text"] == "你"));
    let ranked = session.request(&reverse_request(&original));
    assert_eq!(ranked["candidates"][partial], "你");
    assert_eq!(
        ranked["candidate_ids"][partial],
        original["candidate_ids"][partial]
    );
    let selected =
        session.request(&json!({"op":"select","index":partial,"generation":ranked["generation"]}));
    assert_eq!(selected["commit"], "你");
    assert!(!selected["preedit"].as_str().unwrap().is_empty());
    assert_eq!(selected["ranking"]["applied"], false);
}

#[test]
fn punctuation_commits_visible_ranked_candidate_and_wrong_id_is_rejected() {
    let (_dir, mut session) = fixture(false);
    let original = type_code(&mut session, "nihao");
    let ranked = session.request(&reverse_request(&original));
    let wrong = session.request(
        &json!({"op":"select","index":0,"id":"engine:999","generation":ranked["generation"]}),
    );
    assert_eq!(wrong["error"], "stale_candidate_id");
    assert_eq!(session.request(&json!({"op":"view"})), ranked);
    let punctuation = session.request(&json!({"op":"key","text":","}));
    assert!(punctuation["commit"]
        .as_str()
        .unwrap()
        .starts_with(ranked["candidates"][0].as_str().unwrap()));
    assert_eq!(punctuation["preedit"], "");
}

#[test]
fn nine_key_uses_engine_completeness_and_keeps_locked_reading() {
    let (_dir, mut session) = fixture(false);
    session.request(&json!({"op":"layout","name":"nine_key"}));
    let original = type_code(&mut session, "64426");
    assert!(original["ranking"]["candidates"].as_array().unwrap().len() >= 2);
    let ranked = session.request(&reverse_request(&original));
    assert_eq!(ranked["preedit"], original["preedit"]);
    assert_eq!(ranked["spellings"], original["spellings"]);
    let commit = session.request(&json!({"op":"command","name":"CommitCandidate"}));
    assert_eq!(commit["commit"], ranked["candidates"][0]);
    assert_eq!(commit["preedit"], "");
}

#[test]
fn visible_identity_survives_a_rank_result_before_ui_redraw() {
    for mode in ["click", "space", "punctuation"] {
        let (_dir, mut session) = fixture(false);
        let visible = type_code(&mut session, "nihao");
        let ranked = session.request(&reverse_request(&visible));
        assert_ne!(ranked["candidates"][0], visible["candidates"][0]);
        assert_eq!(
            ranked["composition_revision"],
            visible["composition_revision"]
        );
        let mut request = match mode {
            "click" => {
                json!({"op":"select","index":0,"id":visible["candidate_ids"][0],"generation":visible["generation"]})
            }
            "space" => {
                json!({"op":"command","name":"CommitCandidate","first_candidate_id":visible["candidate_ids"][0]})
            }
            _ => json!({"op":"key","text":",","first_candidate_id":visible["candidate_ids"][0]}),
        };
        request["composition_revision"] = visible["composition_revision"].clone();
        let commit = session.request(&request);
        assert!(
            commit["commit"]
                .as_str()
                .unwrap()
                .starts_with(visible["candidates"][0].as_str().unwrap()),
            "{mode}: {commit}"
        );
        assert_eq!(commit["commit_source"], "engine");
    }
}

#[test]
fn input_change_invalidates_identity_but_does_not_swallow_fast_space() {
    let (_dir, mut session) = fixture(false);
    let earlier = type_code(&mut session, "niha");
    let latest = type_code(&mut session, "o");
    let stale = session.request(&json!({"op":"select","id":earlier["candidate_ids"][0],"composition_revision":earlier["composition_revision"],"index":0,"generation":latest["generation"]}));
    assert_eq!(stale["error"], "stale_generation");
    let commit = session.request(&json!({"op":"command","name":"CommitCandidate","first_candidate_id":earlier["candidate_ids"][0],"composition_revision":earlier["composition_revision"]}));
    assert_eq!(commit["commit"], latest["candidates"][0]);
    assert_eq!(commit["preedit"], "");
}

#[test]
fn visible_pin_preserves_identity_across_rank_and_rejects_actual_input_change() {
    let (_dir, mut session) = fixture(false);
    let visible = type_code(&mut session, "nihao");
    session.request(&reverse_request(&visible));
    let pin = json!({"op":"personal_pin","index":0,"id":visible["candidate_ids"][0],"composition_revision":visible["composition_revision"],"generation":visible["generation"]});
    let pinned = session.request(&pin);
    assert!(pinned.get("error").is_none(), "{pinned}");
    assert_eq!(pinned["candidates"][0], visible["candidates"][0]);
    // A personal-data mutation changes the composition revision as well.
    assert_eq!(session.request(&pin)["error"], "stale_generation");
    let records = session.request(&json!({"op":"personal_list"}));
    assert_eq!(records["records"].as_array().unwrap().len(), 1);
    assert_eq!(
        records["records"][0]["content"]["text"],
        visible["candidates"][0]
    );
}

#[test]
fn visible_nine_key_reading_survives_pure_rank_but_not_input_change() {
    let (_dir, mut session) = fixture(false);
    session.request(&json!({"op":"layout","name":"nine_key"}));
    let visible = type_code(&mut session, "64426");
    assert!(!visible["spellings"].as_array().unwrap().is_empty());
    let ranked = session.request(&reverse_request(&visible));
    assert_ne!(ranked["generation"], visible["generation"]);
    let choose = json!({"op":"nine_spelling","index":0,"composition_revision":visible["composition_revision"],"generation":visible["generation"]});
    let chosen = session.request(&choose);
    assert!(chosen.get("error").is_none(), "{chosen}");
    assert_eq!(chosen["commit"], serde_json::Value::Null);
    assert_ne!(
        chosen["composition_revision"],
        visible["composition_revision"]
    );
    assert_eq!(session.request(&choose)["error"], "stale_generation");
}

#[test]
fn visible_page_is_fixed_before_ranking_with_many_personal_snippets() {
    for saved in [38, 39] {
        let (_dir, mut session) = fixture(false);
        for index in 0..saved {
            let edit = json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"snippet","code":"nihao","text":format!("合成短语{index:02}"),"tags":[]}});
            assert!(session.request(&edit).get("error").is_none());
        }
        let visible = type_code(&mut session, "nihao");
        assert_eq!(visible["candidates"].as_array().unwrap().len(), 40);
        assert_eq!(
            visible["ranking"]["candidates"].as_array().unwrap().len(),
            40 - saved
        );
        let ranked = session.request(&reverse_request(&visible));
        if saved == 39 {
            assert_eq!(ranked["error"], "insufficient_ranking_candidates");
            assert_eq!(session.request(&json!({"op":"view"})), visible);
        } else {
            assert!(ranked.get("error").is_none(), "{ranked}");
            let mut old_ids: Vec<_> = visible["candidate_ids"]
                .as_array()
                .unwrap()
                .iter()
                .map(|id| id.as_str().unwrap())
                .collect();
            let mut new_ids: Vec<_> = ranked["candidate_ids"]
                .as_array()
                .unwrap()
                .iter()
                .map(|id| id.as_str().unwrap())
                .collect();
            old_ids.sort_unstable();
            new_ids.sort_unstable();
            assert_eq!(old_ids, new_ids, "ranking introduced an off-page row");
            for index in 0..saved {
                assert_eq!(ranked["candidates"][index], visible["candidates"][index]);
            }
        }
        let selected = session.request(&json!({"op":"select","index":saved,"id":visible["candidate_ids"][saved],"composition_revision":visible["composition_revision"],"generation":visible["generation"]}));
        assert_eq!(selected["commit"], visible["candidates"][saved]);
        assert_eq!(selected["commit_source"], "engine");
    }
}

#[test]
fn explicit_personal_rule_row_is_excluded_and_never_moved_by_ranking() {
    let (_dir, mut session) = fixture(false);
    let edit = json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"candidate_rule","scheme":"pinyin","code":"nihao","text":"你好","position":2}});
    assert!(session.request(&edit).get("error").is_none());
    let visible = type_code(&mut session, "nihao");
    assert_eq!(visible["candidates"][1], "你好");
    assert_eq!(
        visible["ranking"]["candidates"].as_array().unwrap().len(),
        2
    );
    assert!(visible["ranking"]["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .all(|row| row["text"] != "你好"));
    let ranked = session.request(&reverse_request(&visible));
    assert!(ranked.get("error").is_none(), "{ranked}");
    assert_eq!(ranked["candidates"][1], "你好");
    assert_eq!(ranked["candidate_ids"][1], visible["candidate_ids"][1]);
    assert_ne!(ranked["candidates"][0], visible["candidates"][0]);
}

#[test]
fn same_text_with_different_input_coverage_keeps_its_original_engine_identity() {
    for select_full in [false, true] {
        let (_dir, mut session) = fixture_with_rows(
            false,
            "INSERT INTO tbl_2_n VALUES('ni''hao','nh','你',1000);",
        );
        let visible = type_code(&mut session, "nihao");
        let complete_id = visible["ranking"]["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["text"] == "你")
            .unwrap()["id"]
            .clone();
        let partial = visible["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .enumerate()
            .position(|(index, text)| {
                text == "你" && visible["candidate_ids"][index] != complete_id
            })
            .unwrap();
        let ranked = session.request(&reverse_request(&visible));
        assert!(ranked.get("error").is_none(), "{ranked}");
        assert_eq!(
            ranked["candidate_ids"][partial],
            visible["candidate_ids"][partial]
        );
        let id = if select_full {
            complete_id
        } else {
            visible["candidate_ids"][partial].clone()
        };
        let result = session.request(
            &json!({"op":"select","id":id,"composition_revision":visible["composition_revision"]}),
        );
        assert_eq!(result["commit"], "你");
        assert_eq!(result["commit_source"], "engine");
        assert_eq!(result["preedit"].as_str().unwrap().is_empty(), select_full);
    }
}

#[test]
fn exact_readings_are_ranked_without_promoting_longer_phrase_completions() {
    let extra = "CREATE TABLE tbl_3_n(key TEXT,jp TEXT,value TEXT,weight INTEGER);
                 INSERT INTO tbl_3_n VALUES('ni''hao''a','nha','你好啊',100);
                 INSERT INTO tbl_3_n VALUES('ni''hao''ma','nhm','你好吗',90);";
    for nine_key in [false, true] {
        let (_dir, mut session) = fixture_with_rows(false, extra);
        if nine_key {
            session.request(&json!({"op":"layout","name":"nine_key"}));
        }
        let visible = type_code(&mut session, if nine_key { "64426" } else { "nihao" });
        let pool = visible["ranking"]["candidates"].as_array().unwrap();
        assert!(pool.len() >= 2, "{visible}");
        assert!(pool
            .iter()
            .all(|row| row["text"] != "你好啊" && row["text"] != "你好吗"));
        // The QWERTY prefix provider really offers these longer phrases. Keep
        // their ordinary slots; do not fix the test by removing completions.
        if !nine_key {
            assert!(
                visible["candidates"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|text| text == "你好啊"),
                "{visible}"
            );
        }
        let ranked = session.request(&reverse_request(&visible));
        assert!(ranked.get("error").is_none(), "{ranked}");
        for (index, text) in visible["candidates"].as_array().unwrap().iter().enumerate() {
            if text == "你好啊" || text == "你好吗" {
                assert_eq!(&ranked["candidates"][index], text);
                assert_eq!(
                    ranked["candidate_ids"][index],
                    visible["candidate_ids"][index]
                );
            }
        }
    }
}
