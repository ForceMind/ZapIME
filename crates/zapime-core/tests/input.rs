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
    assert_eq!(commit["commit_source"], "engine");
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
    let raw = session.request(&json!({"op":"command","name":"CommitRaw"}));
    assert_eq!(raw["commit"], "n");
    assert_eq!(raw["commit_source"], "engine");
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
    let reordered = session.request(&json!({"op":"command","name":"CommitCandidate"}));
    assert_eq!(reordered["commit"], "拟好");
    // Reordering a real engine row does not turn it into injected saved text.
    assert_eq!(reordered["commit_source"], "engine");
}

#[test]
fn saved_candidate_provenance_covers_select_space_and_does_not_leak_to_later_replies() {
    for content in [
        json!({"type":"snippet","code":"nihao","text":"合成快捷短语","tags":[]}),
        json!({"type":"dictionary","scheme":"pinyin","code":"nihao","text":"合成个人词库","weight":100}),
        json!({"type":"candidate_rule","scheme":"pinyin","code":"nihao","text":"合成新增规则","position":1}),
        // Equal text is still saved data when a dictionary replaces the engine
        // row with a personal extension; provenance is not string equality.
        json!({"type":"dictionary","scheme":"pinyin","code":"nihao","text":"你好","weight":100}),
    ] {
        let (dir, _private) = fixture();
        let mut session = InputSession::open(
            &dir.path().join("resources"),
            &dir.path().join("data"),
            &dir.path().join("cache"),
            false,
        )
        .unwrap();
        let text = content["text"].clone();
        let edited = session.request(&json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":content}));
        assert!(edited.get("error").is_none(), "{edited}");
        assert_eq!(edited["commit_source"], "none");
        for select in [true, false] {
            let mut view = json!(null);
            for c in "nihao".chars() {
                view = session.request(&json!({"op":"key","text":c.to_string()}));
                assert!(view["commit"].is_null());
                assert_eq!(view["commit_source"], "none");
            }
            assert_eq!(view["candidates"][0], text);
            let generation = view["generation"].as_u64().unwrap();
            let committed = if select {
                session.request(&json!({"op":"select","index":0,"generation":generation}))
            } else {
                session.request(&json!({"op":"command","name":"CommitCandidate"}))
            };
            assert_eq!(committed["commit"], text);
            assert_eq!(committed["commit_source"], "saved_data");
            assert_eq!(committed["preedit"], "");
            assert_eq!(committed["generation"], generation + 1);
            let idle = session.request(&json!({"op":"view"}));
            assert!(idle["commit"].is_null());
            assert_eq!(idle["commit_source"], "none");
            assert_eq!(idle["generation"], committed["generation"]);
            let stale = session.request(&json!({"op":"select","index":0,"generation":generation}));
            assert_eq!(stale["error"], "stale_generation");
            assert!(stale.get("commit").is_none());
            assert!(stale.get("commit_source").is_none());
            let composing = session.request(&json!({"op":"key","text":"n"}));
            assert!(!composing["preedit"].as_str().unwrap().is_empty());
            let cancelled = session.request(&json!({"op":"command","name":"Cancel"}));
            assert_eq!(cancelled["preedit"], "");
            assert!(cancelled["commit"].is_null());
            assert_eq!(cancelled["commit_source"], "none");
            let cancelled = session.request(&json!({"op":"reset"}));
            assert!(cancelled["commit"].is_null());
            assert_eq!(cancelled["commit_source"], "none");
        }
        // The same session subsequently selects another real engine row.
        let mut view = json!(null);
        for c in "nihao".chars() {
            view = session.request(&json!({"op":"key","text":c.to_string()}));
        }
        let engine_index = view["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .position(|candidate| candidate == "拟好")
            .unwrap();
        let engine = session
            .request(&json!({"op":"select","index":engine_index,"generation":view["generation"]}));
        assert_eq!(engine["commit"], "拟好");
        assert_eq!(engine["commit_source"], "engine");
        assert_eq!(
            session.request(&json!({"op":"command","name":"Cancel"}))["commit_source"],
            "none"
        );
        let invalid = session.request(&json!({"op":"command","name":"Invalid"}));
        assert_eq!(invalid["error"], "invalid_command");
        assert!(invalid.get("commit_source").is_none());
    }
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

#[test]
fn nine_key_uses_upstream_decoder_and_generation_checked_spellings() {
    let (_dir, mut session) = fixture();
    let layout = session.request(&json!({"op":"layout","name":"nine_key"}));
    assert_eq!(layout["layout"], "nine_key");
    let mut view = json!(null);
    for key in "64".chars() {
        view = session.request(&json!({"op":"key","text":key.to_string()}));
    }
    let ni = view["spellings"]
        .as_array()
        .unwrap()
        .iter()
        .position(|s| s == "ni")
        .unwrap();
    assert_eq!(
        session
            .request(&json!({"op":"nine_spelling","index":ni,"generation":layout["generation"]}))
            ["error"],
        "stale_generation"
    );
    view =
        session.request(&json!({"op":"nine_spelling","index":ni,"generation":view["generation"]}));
    assert!(view["preedit"].as_str().unwrap().starts_with("ni"));
    assert_eq!(
        session.request(&json!({"op":"layout","name":"qwerty"}))["error"],
        "composition_active"
    );
    for key in "426".chars() {
        view = session.request(&json!({"op":"key","text":key.to_string()}));
    }
    let selected = view["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|s| s == "你好")
        .unwrap();
    let commit =
        session.request(&json!({"op":"select","index":selected,"generation":view["generation"]}));
    assert_eq!(commit["commit"], "你好");
    assert_eq!(commit["preedit"], "");
    assert_eq!(
        session.request(&json!({"op":"nine_spelling","index":0,"generation":commit["generation"]}))
            ["error"],
        "invalid_spelling"
    );
    assert_eq!(
        session.request(&json!({"op":"layout","name":"qwerty"}))["layout"],
        "qwerty"
    );
    for key in "nihao".chars() {
        view = session.request(&json!({"op":"key","text":key.to_string()}));
    }
    assert!(view["candidates"]
        .as_array()
        .unwrap()
        .contains(&json!("你好")));
}

#[test]
fn nine_key_backspace_and_personal_shortcut_stay_local() {
    let (dir, _private) = fixture();
    let mut session = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    assert!(session.request(&json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"snippet","code":"wx","text":"本地九键短语","tags":[]}})).get("error").is_none());
    session.request(&json!({"op":"layout","name":"nine_key"}));
    session.request(&json!({"op":"key","text":"9"}));
    session.request(&json!({"op":"key","text":"9"}));
    assert_eq!(
        session.request(&json!({"op":"command","name":"CommitCandidate"}))["commit"],
        "本地九键短语"
    );
    session.request(&json!({"op":"key","text":"6"}));
    let view = session.request(&json!({"op":"command","name":"Backspace"}));
    assert_eq!(view["preedit"], "");
    assert_eq!(
        session.request(&json!({"op":"layout","name":"invalid"}))["error"],
        "invalid_layout"
    );
}

#[test]
fn nine_key_personal_words_respect_explicit_spelling_disambiguation() {
    let (dir, _private) = fixture();
    let mut session = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    for (code, text) in [("nihao", "你好自定义"), ("mihao", "米号自定义")] {
        assert!(session.request(&json!({"op":"personal_edit","id":uuid::Uuid::new_v4(),"operation_id":uuid::Uuid::new_v4(),"expected_revision":0,"content":{"type":"dictionary","scheme":"pinyin","code":code,"text":text,"weight":100}})).get("error").is_none());
    }
    session.request(&json!({"op":"layout","name":"nine_key"}));
    for (spelling, present, absent) in [
        ("ni", "你好自定义", "米号自定义"),
        ("mi", "米号自定义", "你好自定义"),
    ] {
        session.request(&json!({"op":"key","text":"6"}));
        let view = session.request(&json!({"op":"key","text":"4"}));
        let index = view["spellings"]
            .as_array()
            .unwrap()
            .iter()
            .position(|s| s == spelling)
            .unwrap();
        session
            .request(&json!({"op":"nine_spelling","index":index,"generation":view["generation"]}));
        let mut view = json!(null);
        for key in "426".chars() {
            view = session.request(&json!({"op":"key","text":key.to_string()}));
        }
        let candidates = view["candidates"].as_array().unwrap();
        assert!(candidates.contains(&json!(present)));
        assert!(!candidates.contains(&json!(absent)));
        session.request(&json!({"op":"reset"}));
    }
}

#[test]
fn nine_key_pin_uses_real_pinyin_and_cannot_override_another_locked_reading() {
    let (dir, _private) = fixture();
    let mut session = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    session.request(&json!({"op":"layout","name":"nine_key"}));
    let mut view = json!(null);
    for key in "64426".chars() {
        view = session.request(&json!({"op":"key","text":key.to_string()}));
    }
    let index = view["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|s| s == "拟好")
        .unwrap();
    view = session
        .request(&json!({"op":"personal_pin","index":index,"generation":view["generation"]}));
    assert_eq!(view["candidates"][0], "拟好");
    assert_eq!(
        session.request(&json!({"op":"personal_list"}))["records"][0]["content"]["code"],
        "nihao"
    );
    session.request(&json!({"op":"reset"}));
    session.request(&json!({"op":"key","text":"6"}));
    view = session.request(&json!({"op":"key","text":"4"}));
    let mi = view["spellings"]
        .as_array()
        .unwrap()
        .iter()
        .position(|s| s == "mi")
        .unwrap();
    session.request(&json!({"op":"nine_spelling","index":mi,"generation":view["generation"]}));
    for key in "426".chars() {
        view = session.request(&json!({"op":"key","text":key.to_string()}));
    }
    assert!(!view["candidates"]
        .as_array()
        .unwrap()
        .contains(&json!("拟好")));
    session.request(&json!({"op":"reset"}));
    session.request(&json!({"op":"layout","name":"qwerty"}));
    for key in "nihao".chars() {
        view = session.request(&json!({"op":"key","text":key.to_string()}));
    }
    assert_eq!(view["candidates"][0], "拟好");
}
