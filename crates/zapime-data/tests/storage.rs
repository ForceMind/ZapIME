// SPDX-License-Identifier: GPL-3.0-only
use uuid::Uuid;
use zapime_data::{Content, Error, Store};
fn favorite() -> Content {
    Content::Favorite {
        title: "测试".into(),
        text: "永久保存的合成内容".into(),
    }
}
#[test]
fn atomic_edit_is_idempotent_and_conflicting_replay_is_rejected() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.db")).unwrap();
    let (op, id, dev) = (Uuid::new_v4(), Uuid::new_v4(), Uuid::new_v4());
    let first = store.edit(op, id, 0, dev, favorite(), false).unwrap();
    assert_eq!(
        store.edit(op, id, 0, dev, favorite(), false).unwrap(),
        first
    );
    assert_eq!(store.pending_count().unwrap(), 1);
    assert!(matches!(
        store.edit(Uuid::new_v4(), id, 0, dev, favorite(), false),
        Err(Error::Conflict)
    ));
    assert!(matches!(
        store.edit(op, id, 0, dev, favorite(), true),
        Err(Error::OperationReused)
    ));
    assert_eq!(store.pending_count().unwrap(), 1);
    drop(store);
    assert_eq!(
        Store::open(&dir.path().join("data.db"))
            .unwrap()
            .get(id)
            .unwrap()
            .unwrap(),
        first
    );
}
#[test]
fn clipboard_expiry_preserves_favorites_and_tombstones_survive_restore() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("a.db")).unwrap();
    let dev = Uuid::new_v4();
    store
        .edit(Uuid::new_v4(), Uuid::new_v4(), 0, dev, favorite(), false)
        .unwrap();
    store
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            dev,
            Content::Clipboard {
                text: "临时内容".into(),
                expires_at_ms: 100,
                pinned: true,
            },
            false,
        )
        .unwrap();
    assert_eq!(store.expire_clipboard(100, dev).unwrap(), 1);
    assert_eq!(store.records(false).unwrap().len(), 1);
    let backup = store.export_plaintext().unwrap();
    let mut fresh = Store::open(&dir.path().join("fresh.db")).unwrap();
    fresh.restore_plaintext(&backup).unwrap();
    assert_eq!(fresh.records(true).unwrap(), store.records(true).unwrap());
    assert_eq!(fresh.records(false).unwrap().len(), 1);
    assert!(matches!(
        fresh.restore_plaintext(&backup),
        Err(Error::NotEmpty)
    ));
}
#[test]
fn corrupt_or_future_archive_cannot_partially_restore() {
    let dir = tempfile::tempdir().unwrap();
    let mut source = Store::open(&dir.path().join("a.db")).unwrap();
    source
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            Uuid::new_v4(),
            favorite(),
            false,
        )
        .unwrap();
    let mut archive: serde_json::Value =
        serde_json::from_slice(&source.export_plaintext().unwrap()).unwrap();
    archive["body"]["records"][0]["content"]["text"] = "已篡改".into();
    let mut fresh = Store::open(&dir.path().join("b.db")).unwrap();
    assert!(matches!(
        fresh.restore_plaintext(&serde_json::to_vec(&archive).unwrap()),
        Err(Error::Integrity)
    ));
    assert!(fresh.records(true).unwrap().is_empty());
    archive["body"]["schema_version"] = 2.into();
    assert!(matches!(
        fresh.restore_plaintext(&serde_json::to_vec(&archive).unwrap()),
        Err(Error::Version)
    ));
}
#[test]
fn same_text_with_distinct_codes_is_not_collapsed() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("a.db")).unwrap();
    let dev = Uuid::new_v4();
    for code in ["wx", "weixin"] {
        store
            .edit(
                Uuid::new_v4(),
                Uuid::new_v4(),
                0,
                dev,
                Content::Snippet {
                    code: code.into(),
                    text: "相同正文".into(),
                    tags: vec![],
                },
                false,
            )
            .unwrap();
    }
    assert_eq!(store.records(false).unwrap().len(), 2);
    assert!(store
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            dev,
            Content::CandidateRule {
                scheme: "pinyin".into(),
                code: "zhong".into(),
                text: "中".into(),
                position: 0
            },
            false
        )
        .is_err());
    assert_eq!(store.pending_count().unwrap(), 2);
}

#[test]
fn two_connections_cannot_silently_overwrite_each_other() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("a.db");
    let mut a = Store::open(&path).unwrap();
    let mut b = Store::open(&path).unwrap();
    let id = Uuid::new_v4();
    let dev = Uuid::new_v4();
    a.edit(Uuid::new_v4(), id, 0, dev, favorite(), false)
        .unwrap();
    a.edit(Uuid::new_v4(), id, 1, dev, favorite(), true)
        .unwrap();
    assert!(matches!(
        b.edit(Uuid::new_v4(), id, 1, dev, favorite(), false),
        Err(Error::Conflict)
    ));
    assert!(b.get(id).unwrap().unwrap().deleted);
}

#[test]
fn local_device_identity_is_stable_across_connections() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("a.db");
    let id = Store::open(&path).unwrap().device_id().unwrap();
    assert!(!id.is_nil());
    assert_eq!(Store::open(&path).unwrap().device_id().unwrap(), id);
}

#[test]
fn candidate_slots_and_dictionary_identity_reject_ambiguous_duplicates() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("a.db")).unwrap();
    let dev = Uuid::new_v4();
    let rule = Content::CandidateRule {
        scheme: "pinyin".into(),
        code: "zhong".into(),
        text: "中".into(),
        position: 1,
    };
    store
        .edit(Uuid::new_v4(), Uuid::new_v4(), 0, dev, rule.clone(), false)
        .unwrap();
    assert!(matches!(
        store.edit(Uuid::new_v4(), Uuid::new_v4(), 0, dev, rule, false),
        Err(Error::PositionConflict)
    ));
    let entry = Content::Dictionary {
        scheme: "pinyin".into(),
        code: "zhong".into(),
        text: "中".into(),
        weight: 100,
    };
    store
        .edit(Uuid::new_v4(), Uuid::new_v4(), 0, dev, entry.clone(), false)
        .unwrap();
    assert!(matches!(
        store.edit(Uuid::new_v4(), Uuid::new_v4(), 0, dev, entry, false),
        Err(Error::Duplicate)
    ));
    assert_eq!(store.pending_count().unwrap(), 2);
}

#[test]
fn correctly_checksummed_archive_with_conflicting_rules_is_still_rejected() {
    use serde::Serialize;
    use sha2::{Digest, Sha256};
    #[derive(Serialize)]
    struct Body {
        schema_version: u32,
        records: Vec<zapime_data::Record>,
    }
    let dir = tempfile::tempdir().unwrap();
    let mut source = Store::open(&dir.path().join("a.db")).unwrap();
    let first = source
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            Uuid::new_v4(),
            Content::CandidateRule {
                scheme: "pinyin".into(),
                code: "zhong".into(),
                text: "中".into(),
                position: 1,
            },
            false,
        )
        .unwrap();
    let mut duplicate = first.clone();
    duplicate.id = Uuid::new_v4();
    let body = Body {
        schema_version: 1,
        records: vec![first, duplicate],
    };
    let hash = hex::encode(Sha256::digest(serde_json::to_vec(&body).unwrap()));
    let archive = serde_json::to_vec(&serde_json::json!({"body":body,"sha256":hash})).unwrap();
    let mut target = Store::open(&dir.path().join("fresh.db")).unwrap();
    assert!(matches!(
        target.restore_plaintext(&archive),
        Err(Error::PositionConflict)
    ));
    assert!(target.records(true).unwrap().is_empty());
}
