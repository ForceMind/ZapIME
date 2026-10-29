// SPDX-License-Identifier: GPL-3.0-only
use serde_json::{json, Value};
use uuid::Uuid;
use zapime_core::InputSession;
use zapime_data::{Content, Store};

fn fixture(private: bool) -> (tempfile::TempDir, InputSession) {
    let dir = tempfile::tempdir().unwrap();
    let resources = dir.path().join("resources");
    std::fs::create_dir(&resources).unwrap();
    let db = rusqlite::Connection::open(resources.join("msime-pinyin.db")).unwrap();
    db.execute_batch("CREATE TABLE tbl_2_n(key TEXT,jp TEXT,value TEXT,weight INTEGER); INSERT INTO tbl_2_n VALUES('ni''hao','nh','你好',200);").unwrap();
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
fn save(session: &mut InputSession, content: Value) -> Value {
    let id = Uuid::new_v4();
    let result=session.request(&json!({"op":"personal_edit","id":id,"operation_id":Uuid::new_v4(),"expected_revision":0,"content":content}));
    assert!(result.get("error").is_none(), "{result}");
    session.request(&json!({"op":"personal_list"}))["records"]
        .as_array()
        .unwrap()
        .iter()
        .find(|r| r["id"] == id.to_string())
        .unwrap()
        .clone()
}
#[test]
fn queries_search_unicode_tags_and_bound_pages_without_changing_composition() {
    let (_dir, mut s) = fixture(false);
    save(
        &mut s,
        json!({"type":"snippet","code":"wx","text":"联系测试","tags":["Office"]}),
    );
    save(
        &mut s,
        json!({"type":"favorite","title":"资料","text":"Chinese TEXT"}),
    );
    for _ in 0..45 {
        save(
            &mut s,
            json!({"type":"favorite","title":"长资料","text":"长".repeat(500)}),
        );
    }
    s.request(&json!({"op":"key","text":"n"}));
    let before = s.request(&json!({"op":"view"}));
    for query in ["WX", "OFFICE", "联系"] {
        let page = s.request(&json!({"op":"personal_query","kind":"snippet","query":query}));
        assert_eq!(page["total"], 1);
        assert_eq!(page["items"][0]["label"], "wx");
        assert!(page["items"][0].get("content").is_none());
    }
    let page = s.request(&json!({"op":"personal_query","kind":"favorite","query":"长","limit":40}));
    assert_eq!(page["items"].as_array().unwrap().len(), 40);
    assert_eq!(page["total"], 45);
    assert_eq!(page["next_offset"], 40);
    assert_eq!(
        page["items"][0]["preview"]
            .as_str()
            .unwrap()
            .chars()
            .count(),
        96
    );
    let tail = s.request(
        &json!({"op":"personal_query","kind":"favorite","query":"长","offset":40,"limit":40}),
    );
    assert_eq!(tail["items"].as_array().unwrap().len(), 5);
    assert!(tail["next_offset"].is_null());
    assert_eq!(s.request(&json!({"op":"view"})), before);
    for args in [
        json!({"kind":"bad"}),
        json!({"limit":0}),
        json!({"limit":41}),
        json!({"offset":-1}),
        json!({"query":7}),
        json!({"query":"a".repeat(513)}),
    ] {
        let mut request = args;
        request["op"] = json!("personal_query");
        assert_eq!(s.request(&request)["error"], "invalid_query");
    }
}
#[test]
fn latest_store_revision_controls_full_body_insertion_and_deleted_rejection() {
    let (dir, mut s) = fixture(false);
    let record = save(
        &mut s,
        json!({"type":"favorite","title":"长内容","text":"原文"}),
    );
    let id = Uuid::parse_str(record["id"].as_str().unwrap()).unwrap();
    let revision = record["revision"].as_u64().unwrap();
    let mut store = Store::open(&dir.path().join("data/personal.sqlite3")).unwrap();
    let device = store.device_id().unwrap();
    let full = "完整内容\n".repeat(300);
    let changed = store
        .edit(
            Uuid::new_v4(),
            id,
            revision,
            device,
            Content::Favorite {
                title: "新标题".into(),
                text: full.clone(),
            },
            false,
        )
        .unwrap();
    let page = s.request(&json!({"op":"personal_query","query":"新标题"}));
    assert_eq!(page["total"], 1);
    assert_eq!(
        s.request(&json!({"op":"personal_commit","id":id,"expected_revision":revision}))["error"],
        "revision_conflict"
    );
    s.request(&json!({"op":"key","text":"n"}));
    assert_eq!(
        s.request(&json!({"op":"personal_commit","id":id,"expected_revision":changed.revision}))
            ["error"],
        "composition_active"
    );
    s.request(&json!({"op":"reset"}));
    let committed =
        s.request(&json!({"op":"personal_commit","id":id,"expected_revision":changed.revision}));
    assert_eq!(committed["commit"], full);
    assert_eq!(committed["commit_source"], "saved_data");
    let idle = s.request(&json!({"op":"view"}));
    assert!(idle["commit"].is_null());
    assert_eq!(idle["commit_source"], "none");
    store
        .edit(
            Uuid::new_v4(),
            id,
            changed.revision,
            device,
            changed.content,
            true,
        )
        .unwrap();
    assert_eq!(s.request(&json!({"op":"personal_query"}))["total"], 0);
    assert_eq!(
        s.request(&json!({"op":"personal_commit","id":id,"expected_revision":changed.revision}))
            ["error"],
        "record_unavailable"
    );
}
#[test]
fn expired_clipboard_never_inserts_even_if_pinned_and_favorites_remain() {
    let (_dir, mut s) = fixture(false);
    let expired = save(
        &mut s,
        json!({"type":"clipboard","text":"已过期","expires_at_ms":1,"pinned":true}),
    );
    save(
        &mut s,
        json!({"type":"favorite","title":"永久","text":"永久收藏"}),
    );
    assert_eq!(s.request(&json!({"op":"personal_query"}))["total"], 1);
    assert_eq!(s.request(&json!({"op":"personal_commit","id":expired["id"],"expected_revision":expired["revision"]}))["error"],"record_unavailable");
}
#[test]
fn pin_favorite_and_delete_are_revision_checked_and_persisted() {
    let (dir, mut s) = fixture(false);
    let a = save(
        &mut s,
        json!({"type":"clipboard","text":"保留内容","expires_at_ms":u64::MAX,"pinned":false}),
    );
    let b = save(
        &mut s,
        json!({"type":"clipboard","text":"其它内容","expires_at_ms":u64::MAX,"pinned":false}),
    );
    let action = |record: &Value, name: &str| json!({"op":"personal_action","id":record["id"],"expected_revision":record["revision"],"operation_id":Uuid::new_v4(),"action":name});
    assert!(s.request(&action(&a, "pin")).get("error").is_none());
    let page = s.request(&json!({"op":"personal_query","kind":"clipboard"}));
    assert_eq!(page["items"][0]["id"], a["id"]);
    assert_eq!(page["items"][0]["pinned"], true);
    assert_eq!(
        s.request(&action(&a, "delete"))["error"],
        "revision_conflict"
    );
    let current = &page["items"][0];
    let favorite_id = Uuid::new_v4();
    let mut favorite = action(current, "favorite");
    favorite["destination_id"] = json!(favorite_id);
    assert!(s.request(&favorite).get("error").is_none());
    assert!(s.request(&action(current, "delete")).get("error").is_none());
    assert_eq!(
        s.request(&json!({"op":"personal_query","kind":"clipboard"}))["items"][0]["id"],
        b["id"]
    );
    drop(s);
    let mut reopened = InputSession::open(
        &dir.path().join("resources"),
        &dir.path().join("data"),
        &dir.path().join("cache"),
        false,
    )
    .unwrap();
    let committed =
        reopened.request(&json!({"op":"personal_commit","id":favorite_id,"expected_revision":1}));
    assert_eq!(committed["commit"], "保留内容");
    assert_eq!(committed["commit_source"], "saved_data");
}
#[test]
fn private_sessions_cannot_query_commit_or_mutate_saved_data() {
    let (_dir, mut s) = fixture(true);
    for op in ["personal_query", "personal_commit", "personal_action"] {
        assert_eq!(s.request(&json!({"op":op}))["error"], "private_session");
    }
}

#[test]
fn settings_reload_sees_stateless_restore_and_rejects_active_or_private_sessions() {
    let (_source_dir, mut source) = fixture(false);
    let expected = save(
        &mut source,
        json!({"type":"favorite","title":"synthetic recovery","text":"restored immediately"}),
    );
    let archive = source.request(&json!({"op":"personal_export"}))["archive"]
        .as_str()
        .unwrap()
        .as_bytes()
        .to_vec();
    let (dir, mut target) = fixture(false);
    assert_eq!(
        target.request(&json!({"op":"personal_list"}))["records"],
        json!([])
    );
    let mut writer = Store::open_backup_writer(&dir.path().join("data/personal.sqlite3")).unwrap();
    assert_eq!(writer.restore_unbound_plaintext(&archive).unwrap(), 1);
    assert_eq!(
        target.request(&json!({"op":"personal_list"}))["records"],
        json!([])
    );
    let before = target.request(&json!({"op":"view"}));
    assert_eq!(
        target.request(&json!({"op":"personal_reload"}))["records"],
        json!([expected])
    );
    let after = target.request(&json!({"op":"view"}));
    assert_eq!(
        after["generation"].as_u64().unwrap(),
        before["generation"].as_u64().unwrap() + 1
    );
    assert_eq!(after["commit"], before["commit"]);
    assert_eq!(after["preedit"], before["preedit"]);
    target.request(&json!({"op":"key","text":"n"}));
    let composing = target.request(&json!({"op":"view"}));
    assert_eq!(
        target.request(&json!({"op":"personal_reload"}))["error"],
        "composition_active"
    );
    assert_eq!(target.request(&json!({"op":"view"})), composing);
    let (_dir, mut private) = fixture(true);
    assert_eq!(
        private.request(&json!({"op":"personal_reload"}))["error"],
        "private_session"
    );
}
