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
