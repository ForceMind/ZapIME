// SPDX-License-Identifier: GPL-3.0-only
use serde_json::json;
use zapime_core::InputSession;

fn fixture() -> (tempfile::TempDir, InputSession) {
    let dir = tempfile::tempdir().unwrap();
    let resources = dir.path().join("resources");
    std::fs::create_dir(&resources).unwrap();
    let db = rusqlite::Connection::open(resources.join("msime-pinyin.db")).unwrap();
    db.execute_batch("CREATE TABLE tbl_2_n(key TEXT,jp TEXT,value TEXT,weight INTEGER); INSERT INTO tbl_2_n VALUES('ni''hao','nh','你好',200); INSERT INTO tbl_2_n VALUES('ni''hao','nh','拟好',100);").unwrap();
    msime_engine::ensure_english_schema(&resources.join("msime-english.db")).unwrap();
    let session = InputSession::open(
        &resources,
        &dir.path().join("data"),
        &dir.path().join("cache"),
        true,
    )
    .unwrap();
    (dir, session)
}

#[test]
fn offline_pinyin_selects_and_clears_composition() {
    let (_dir, mut session) = fixture();
    let mut view = json!(null);
    for c in "nihao".chars() {
        view = session.request(&json!({"op":"key","text":c.to_string()}));
    }
    assert!(view["candidates"]
        .as_array()
        .unwrap()
        .contains(&json!("你好")));
    let index = view["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|x| x == "你好")
        .unwrap();
    let commit =
        session.request(&json!({"op":"select","index":index,"generation":view["generation"]}));
    assert_eq!(commit["commit"], "你好");
    assert_eq!(commit["preedit"], "");
}

#[test]
fn stale_candidate_cannot_commit_after_a_new_key_or_reset() {
    let (_dir, mut session) = fixture();
    for c in "nihao".chars() {
        session.request(&json!({"op":"key","text":c.to_string()}));
    }
    session.request(&json!({"op":"reset"}));
    assert_eq!(
        session.request(&json!({"op":"select","index":0,"generation":5}))["error"],
        "stale_generation"
    );
    assert_eq!(
        session.request(&json!({"op":"view"}))["commit"],
        json!(null)
    );
}

#[test]
fn invalid_unicode_and_out_of_range_selection_do_not_mutate_session() {
    let (_dir, mut session) = fixture();
    let before = session.request(&json!({"op":"view"}));
    for text in ["你好", "ab", "😀", ""] {
        assert_eq!(
            session.request(&json!({"op":"key","text":text}))["error"],
            "invalid_key"
        );
    }
    assert_eq!(
        session.request(&json!({"op":"select","index":usize::MAX,"generation":0}))["error"],
        "invalid_candidate"
    );
    assert_eq!(session.request(&json!({"op":"view"})), before);
}

#[test]
fn backspace_raw_commit_and_focus_reset() {
    let (_dir, mut session) = fixture();
    session.request(&json!({"op":"key","text":"n"}));
    session.request(&json!({"op":"key","text":"i"}));
    session.request(&json!({"op":"command","name":"Backspace"}));
    assert_eq!(
        session.request(&json!({"op":"command","name":"CommitRaw"}))["commit"],
        "n"
    );
    session.request(&json!({"op":"key","text":"h"}));
    session.request(&json!({"op":"reset"}));
    assert_eq!(session.request(&json!({"op":"view"}))["preedit"], "");
}

#[test]
fn persisted_user_candidate_rule_changes_selection_and_survives_reopen() {
    let (dir, _private) = fixture();
    let resources = dir.path().join("resources");
    let data = dir.path().join("data");
    let cache = dir.path().join("cache");
    let mut session = InputSession::open(&resources, &data, &cache, false).unwrap();
    let edit = json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"candidate_rule","scheme":"pinyin","code":"nihao","text":"拟好","position":1}});
    assert!(session.request(&edit).get("error").is_none());
    drop(session);
    let mut session = InputSession::open(&resources, &data, &cache, false).unwrap();
    let mut view = json!(null);
    for c in "nihao".chars() {
        view = session.request(&json!({"op":"key","text":c.to_string()}));
    }
    assert_eq!(view["candidates"][0], "拟好");
    assert_eq!(
        session.request(&json!({"op":"command","name":"CommitCandidate"}))["commit"],
        "拟好"
    );
}

#[test]
fn shortcut_can_commit_custom_text_without_replacing_pinyin_decoder() {
    let (dir, _private) = fixture();
    let mut session = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    let edit = json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"snippet","code":"wx","text":"合成快捷短语","tags":[]}});
    assert!(session.request(&edit).get("error").is_none());
    for c in "wx".chars() {
        session.request(&json!({"op":"key","text":c.to_string()}));
    }
    assert_eq!(
        session.request(&json!({"op":"command","name":"CommitCandidate"}))["commit"],
        "合成快捷短语"
    );
    assert_eq!(session.request(&json!({"op":"view"}))["preedit"], "");
    let mut private = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        true,
    )
    .unwrap();
    assert_eq!(private.request(&edit)["error"], "private_session");
}

#[test]
fn candidate_pin_reorders_visible_rows_and_rejects_old_click() {
    let (dir, _private) = fixture();
    let mut session = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    let mut view = json!(null);
    for c in "nihao".chars() {
        view = session.request(&json!({"op":"key","text":c.to_string()}));
    }
    let index = view["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|v| v == "拟好")
        .unwrap();
    let pinned = session
        .request(&json!({"op":"personal_pin","generation":view["generation"],"index":index}));
    assert_eq!(pinned["candidates"][0], "拟好");
    assert_eq!(
        session.request(&json!({"op":"select","generation":view["generation"],"index":0}))["error"],
        "stale_generation"
    );
    assert_eq!(
        session.request(&json!({"op":"command","name":"CommitCandidate"}))["commit"],
        "拟好"
    );
}

#[test]
fn personal_archive_api_restores_only_into_a_new_profile() {
    let (dir, _private) = fixture();
    let resources = dir.path().join("resources");
    let mut source = InputSession::open(
        &resources,
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    assert!(source.request(&json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"favorite","title":"测试","text":"标准格式恢复"}})).get("error").is_none());
    let archive = source.request(&json!({"op":"personal_export"}));
    let mut destination = InputSession::open(
        &resources,
        &dir.path().join("fresh-data"),
        &dir.path().join("fresh-cache"),
        false,
    )
    .unwrap();
    assert!(destination
        .request(&json!({"op":"personal_import","archive":archive["archive"]}))
        .get("error")
        .is_none());
    assert_eq!(
        source.request(&json!({"op":"personal_list"})),
        destination.request(&json!({"op":"personal_list"}))
    );
    assert_eq!(
        destination.request(&json!({"op":"personal_import","archive":archive["archive"]}))["error"],
        "restore_requires_empty_profile"
    );
}

#[test]
fn pinning_another_candidate_replaces_the_previous_first_slot() {
    let (dir, _private) = fixture();
    let mut session = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    let mut view = json!(null);
    for c in "nihao".chars() {
        view = session.request(&json!({"op":"key","text":c.to_string()}));
    }
    for text in ["拟好", "你好"] {
        let index = view["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .position(|v| v == text)
            .unwrap();
        view = session
            .request(&json!({"op":"personal_pin","generation":view["generation"],"index":index}));
        assert_eq!(view["candidates"][0], text);
    }
    assert_eq!(
        session.request(&json!({"op":"personal_list"}))["records"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
}
