// SPDX-License-Identifier: GPL-3.0-only
//! Linux/desktop Rust integration: real pinned pinyin decoder, local AEAD and
//! two independent SQLite profiles. Only server ACK/page metadata is simulated.
//! Not Android system IME, HTTPS, phone, live cache refresh or full E2EE recovery.
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use uuid::Uuid;
use zapime_core::InputSession;
use zapime_data::{inventory::InventoryState, sync::UploadOperation, Content, Record, Store};
use zapime_sync::{
    client,
    workspace::{self, Material},
    Change, Page,
};

struct Pair {
    directory: tempfile::TempDir,
    resources: PathBuf,
    b_root: PathBuf,
    a: InputSession,
    b: Option<InputSession>,
    a_store: Store,
    b_store: Store,
    a_material: Material,
    b_material: Material,
    profile: Uuid,
    server_device: Uuid,
    revision: u64,
}
impl Pair {
    fn new() -> Self {
        let directory = tempfile::tempdir().unwrap();
        let resources = directory.path().join("resources");
        std::fs::create_dir(&resources).unwrap();
        let dictionary = rusqlite::Connection::open(resources.join("msime-pinyin.db")).unwrap();
        dictionary
            .execute_batch(
                "CREATE TABLE tbl_2_n(key TEXT,jp TEXT,value TEXT,weight INTEGER);
            INSERT INTO tbl_2_n VALUES('ni''hao','nh','你好',200);
            INSERT INTO tbl_2_n VALUES('ni''hao','nh','拟好',100);",
            )
            .unwrap();
        msime_engine::ensure_english_schema(&resources.join("msime-english.db")).unwrap();
        let a_root = directory.path().join("a");
        let b_root = directory.path().join("b");
        let a = open(&resources, &a_root, false);
        let b = open(&resources, &b_root, false);
        let profile = Uuid::new_v4();
        let a_material = Material::generate(profile).unwrap();
        let b_material = Material::decode(profile, &a_material.encode()).unwrap();
        assert_eq!(a_material.descriptor(), b_material.descriptor());
        // Match the Android JNI physical-root boundary; NOFOLLOW rejects
        // platform temp aliases such as macOS /var as well as leaf symlinks.
        let a_path = std::fs::canonicalize(a_root.join("data/personal.sqlite3")).unwrap();
        let b_path = std::fs::canonicalize(b_root.join("data/personal.sqlite3")).unwrap();
        assert!(
            workspace::prepare(&a_path, profile, &a_material)
                .unwrap()
                .prepared
        );
        assert!(
            workspace::prepare(&b_path, profile, &b_material)
                .unwrap()
                .prepared
        );
        let a_store = Store::open_sync_writer(&a_path).unwrap();
        let b_store = Store::open_sync_writer(&b_path).unwrap();
        Self {
            directory,
            resources,
            b_root,
            a,
            b: Some(b),
            a_store,
            b_store,
            a_material,
            b_material,
            profile,
            server_device: Uuid::new_v4(),
            revision: 0,
        }
    }
    fn edit(&mut self, id: Uuid, expected: u64, content: &Content, deleted: bool) -> Record {
        let reply = self.a.request(&json!({
            "op":"personal_edit", "id":id, "operation_id":Uuid::new_v4(),
            "expected_revision":expected, "content":content, "deleted":deleted,
        }));
        assert!(
            reply.get("error").is_none(),
            "synthetic native personal edit"
        );
        self.a_store.get(id).unwrap().unwrap()
    }
    fn exchange_next(&mut self) -> UploadOperation {
        let operation = client::stage_next(&mut self.a_store, self.profile, &self.a_material)
            .unwrap()
            .unwrap();
        let wire = client::frozen_wire(
            &mut self.a_store,
            self.profile,
            &self.a_material,
            &operation,
        )
        .unwrap();
        assert!(serde_json::from_slice::<UploadOperation>(&wire).unwrap() == operation);
        let after = self.revision;
        self.revision += 1;
        let change = Change {
            account_id: self.profile,
            device_id: self.server_device,
            revision: self.revision,
            operation: operation.clone(),
        };
        client::acknowledge(
            &mut self.a_store,
            self.profile,
            self.server_device,
            &self.a_material,
            &operation,
            &serde_json::to_vec(&change).unwrap(),
        )
        .unwrap();
        assert_eq!(
            client::pull_cursor(&mut self.a_store, self.profile, &self.a_material).unwrap(),
            after
        );
        let page = serde_json::to_vec(&Page {
            changes: vec![change],
            next_cursor: self.revision,
            head: self.revision,
        })
        .unwrap();
        let received = client::apply_page(
            &mut self.b_store,
            self.profile,
            &self.b_material,
            after,
            &page,
        )
        .unwrap();
        assert_eq!(received.applied, 1);
        assert_eq!(received.echoed, 0);
        assert_eq!(received.cursor, self.revision);
        let echoed = client::apply_page(
            &mut self.a_store,
            self.profile,
            &self.a_material,
            after,
            &page,
        )
        .unwrap();
        assert_eq!(echoed.echoed, 1);
        assert_eq!(echoed.applied, 0);
        assert_eq!(self.b_store.pending_count().unwrap(), 0);
        assert!(
            client::stage_next(&mut self.b_store, self.profile, &self.b_material)
                .unwrap()
                .is_none()
        );
        assert_eq!(
            self.a_store.get(operation.record_id).unwrap(),
            self.b_store.get(operation.record_id).unwrap()
        );
        operation
    }
    fn reopen_b(&mut self, private: bool) -> InputSession {
        drop(self.b.take());
        open(&self.resources, &self.b_root, private)
    }
    fn records_only_backup_roundtrip(&mut self, id: Uuid) {
        let expected = self.b_store.get(id).unwrap().unwrap();
        let archive = self
            .b_store
            .export_backup_snapshot(4 * 1024 * 1024)
            .unwrap();
        assert_eq!(archive, self.b_store.export_plaintext().unwrap());
        let mut restored =
            Store::open(&self.directory.path().join("restored-records.sqlite3")).unwrap();
        assert_eq!(
            restored.restore_unbound_plaintext(&archive).unwrap(),
            self.b_store.records(true).unwrap().len()
        );
        assert_eq!(restored.get(id).unwrap(), Some(expected));
        assert!(restored.sync_profile().unwrap().is_none());
        // This intentionally validates records-only portability, not cloud
        // keys, server history, pairing or full lost-device disaster recovery.
    }
}
fn open(resources: &Path, root: &Path, private: bool) -> InputSession {
    InputSession::open(resources, &root.join("data"), &root.join("cache"), private).unwrap()
}
fn type_keys(session: &mut InputSession, keys: &str) -> Value {
    let mut view = json!(null);
    for key in keys.chars() {
        view = session.request(&json!({"op":"key","text":key.to_string()}));
        assert!(view.get("error").is_none(), "real engine key accepted");
    }
    view
}
fn select(session: &mut InputSession, view: &Value, text: &str) -> Value {
    let index = view["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .position(|candidate| candidate == text)
        .expect("actual candidate exists");
    session.request(&json!({"op":"select","index":index,"generation":view["generation"]}))
}

#[test]
fn synced_candidate_rule_is_used_after_reopen_while_old_composition_retains_its_snapshot() {
    let mut pair = Pair::new();
    let old = type_keys(pair.b.as_mut().unwrap(), "nihao");
    assert_eq!(old["candidates"][0], "你好");
    let id = Uuid::new_v4();
    let custom = "同步候选合成词";
    let content = Content::CandidateRule {
        scheme: "pinyin".into(),
        code: "nihao".into(),
        text: custom.into(),
        position: 1,
    };
    let original = pair.edit(id, 0, &content, false);
    let uploaded = pair.exchange_next();
    assert_eq!(uploaded.record_id, id);
    assert_eq!(pair.b_store.get(id).unwrap(), Some(original));
    // Explicitly document the current non-hot-refresh boundary, rather than
    // pretending a background database write already changed a live handle.
    assert_eq!(pair.b.as_mut().unwrap().request(&json!({"op":"view"})), old);
    assert_eq!(
        pair.b
            .as_mut()
            .unwrap()
            .request(&json!({"op":"personal_list"}))["records"],
        json!([])
    );
    assert_eq!(
        pair.b
            .as_mut()
            .unwrap()
            .request(&json!({"op":"personal_reload"}))["error"],
        "composition_active"
    );
    assert_eq!(pair.b.as_mut().unwrap().request(&json!({"op":"view"})), old);
    let mut reopened = pair.reopen_b(false);
    let view = type_keys(&mut reopened, "nihao");
    assert_eq!(view["candidates"][0], custom);
    let committed = select(&mut reopened, &view, custom);
    assert_eq!(committed["commit"], custom);
    assert_eq!(committed["commit_source"], "saved_data");
    assert_eq!(committed["preedit"], "");
    assert_eq!(pair.b_store.pending_count().unwrap(), 0);
    pair.records_only_backup_roundtrip(id);
}

#[test]
fn synced_dictionary_entries_work_in_nine_key_and_obey_explicit_reading_and_private_mode() {
    let mut pair = Pair::new();
    let ni = "同步你好自定义";
    let mi = "同步米号自定义";
    for (code, text) in [("nihao", ni), ("mihao", mi)] {
        pair.edit(
            Uuid::new_v4(),
            0,
            &Content::Dictionary {
                scheme: "pinyin".into(),
                code: code.into(),
                text: text.into(),
                weight: 100,
            },
            false,
        );
    }
    pair.exchange_next();
    pair.exchange_next();
    assert_eq!(pair.a_store.pending_count().unwrap(), 0);
    let mut reopened = pair.reopen_b(false);
    assert_eq!(
        reopened.request(&json!({"op":"layout","name":"nine_key"}))["layout"],
        "nine_key"
    );
    for (reading, present, absent) in [("ni", ni, mi), ("mi", mi, ni)] {
        let choices = type_keys(&mut reopened, "64");
        let index = choices["spellings"]
            .as_array()
            .unwrap()
            .iter()
            .position(|spelling| spelling == reading)
            .expect("real upstream nine-key spelling");
        let locked = reopened.request(
            &json!({"op":"nine_spelling","index":index,"generation":choices["generation"]}),
        );
        assert!(locked.get("error").is_none());
        let view = type_keys(&mut reopened, "426");
        let candidates = view["candidates"].as_array().unwrap();
        assert!(candidates.contains(&json!(present)));
        assert!(!candidates.contains(&json!(absent)));
        let committed = select(&mut reopened, &view, present);
        assert_eq!(committed["commit"], present);
        assert_eq!(committed["commit_source"], "saved_data");
        assert_eq!(committed["preedit"], "");
    }
    assert_eq!(pair.b_store.pending_count().unwrap(), 0);
    let mut private = open(&pair.resources, &pair.b_root, true);
    let view = type_keys(&mut private, "nihao");
    assert_eq!(view["candidates"][0], "你好");
    assert!(!view["candidates"].as_array().unwrap().contains(&json!(ni)));
    assert!(!view["candidates"].as_array().unwrap().contains(&json!(mi)));
    let committed = select(&mut private, &view, "你好");
    assert_eq!(committed["commit"], "你好");
    assert_eq!(committed["commit_source"], "engine");
}

#[test]
fn synced_candidate_rule_tombstone_stays_deleted_in_new_sessions_and_records_backup() {
    let mut pair = Pair::new();
    let id = Uuid::new_v4();
    let custom = "同步删除候选合成词";
    let content = Content::CandidateRule {
        scheme: "pinyin".into(),
        code: "nihao".into(),
        text: custom.into(),
        position: 1,
    };
    pair.edit(id, 0, &content, false);
    pair.exchange_next();
    let mut first = pair.reopen_b(false);
    let view = type_keys(&mut first, "nihao");
    assert_eq!(view["candidates"][0], custom);
    assert_eq!(select(&mut first, &view, custom)["commit"], custom);
    let tombstone = pair.edit(id, 1, &content, true);
    assert!(tombstone.deleted);
    let deleted = pair.exchange_next();
    assert!(deleted.deleted);
    assert_eq!(pair.b_store.get(id).unwrap(), Some(tombstone));
    drop(first);
    let mut reopened = pair.reopen_b(false);
    let view = type_keys(&mut reopened, "nihao");
    assert_eq!(view["candidates"][0], "你好");
    assert!(!view["candidates"]
        .as_array()
        .unwrap()
        .contains(&json!(custom)));
    let committed = select(&mut reopened, &view, "你好");
    assert_eq!(committed["commit"], "你好");
    assert_eq!(committed["commit_source"], "engine");
    assert!(pair.b_store.records(false).unwrap().is_empty());
    pair.records_only_backup_roundtrip(id);
}

#[test]
fn native_records_import_then_one_edit_keeps_inventory_fixture_history_and_indexes() {
    // Mirror the Android UI fixture through production requests, including the
    // records-only boundary. This is a host semantic check, not an Activity run.
    let mut pair = Pair::new();
    let content = Content::Favorite {
        title: "synthetic pages".into(),
        text: "页面生命周期合成资料".into(),
    };
    for index in 1..=43 {
        pair.edit(Uuid::from_u128(index), 0, &content, index % 7 == 0);
    }
    let archive = pair.a.request(&json!({"op":"personal_export"}));
    let root = pair.directory.path().join("inventory-restored");
    let mut restored = open(&pair.resources, &root, false);
    let imported = restored.request(&json!({
        "op":"personal_import", "archive":archive["archive"].as_str().unwrap(),
    }));
    assert!(imported.get("error").is_none());
    let path = std::fs::canonicalize(root.join("data/personal.sqlite3")).unwrap();
    let mut store = Store::open_sync_writer(&path).unwrap();
    assert_eq!(store.records(true).unwrap().len(), 43);
    assert_eq!(store.pending_count().unwrap(), 0);
    let edited = restored.request(&json!({
        "op":"personal_edit", "id":Uuid::from_u128(44),
        "operation_id":Uuid::new_v4(), "expected_revision":0, "content":content,
    }));
    assert!(edited.get("error").is_none());
    drop(restored);
    assert!(
        workspace::prepare(&path, pair.profile, &pair.a_material)
            .unwrap()
            .prepared
    );
    let frozen = client::stage_next(&mut store, pair.profile, &pair.a_material)
        .unwrap()
        .unwrap();
    assert_eq!(frozen.record_id, Uuid::from_u128(44));
    let wire = client::frozen_wire(&mut store, pair.profile, &pair.a_material, &frozen).unwrap();
    let before = store.export_plaintext().unwrap();
    let mut reader = Store::open_sync_reader(&path).unwrap();
    let first =
        client::inventory_page(&mut reader, pair.profile, &pair.a_material, None, 40).unwrap();
    let last = client::inventory_page(
        &mut reader,
        pair.profile,
        &pair.a_material,
        first.next_after,
        40,
    )
    .unwrap();
    assert_eq!(first.items.len(), 40);
    assert_eq!(first.next_after, Some(Uuid::from_u128(40)));
    assert_eq!(last.items.len(), 4);
    assert!(last.next_after.is_none());
    let items = first.items.iter().chain(&last.items).collect::<Vec<_>>();
    assert_eq!(items.iter().filter(|item| item.deleted).count(), 6);
    for (index, item) in items.iter().enumerate() {
        let pending = index == 43;
        assert_eq!(item.id, Uuid::from_u128(index as u128 + 1));
        assert_eq!(
            item.state,
            if pending {
                InventoryState::PendingLocalOperation
            } else {
                InventoryState::Untracked
            }
        );
        assert_eq!(item.has_local_operation_history, pending);
        assert_eq!(item.has_pending_local_operation, pending);
        assert!(item.known_server_revision.is_none());
    }
    let db =
        rusqlite::Connection::open_with_flags(&path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
            .unwrap();
    assert_eq!(
        db.query_row::<u32, _, _>("SELECT count(*) FROM operations", [], |row| row.get(0))
            .unwrap(),
        1
    );
    assert_eq!(
        db.query_row::<u32, _, _>(
            "SELECT count(*) FROM sqlite_master WHERE type='index' AND name IN ('panel_order','sync_pending_record')",
            [],
            |row| row.get(0),
        ).unwrap(),
        2
    );
    assert_eq!(store.pending_count().unwrap(), 1);
    assert_eq!(store.export_plaintext().unwrap(), before);
    assert_eq!(
        client::frozen_wire(&mut store, pair.profile, &pair.a_material, &frozen).unwrap(),
        wire
    );
}
