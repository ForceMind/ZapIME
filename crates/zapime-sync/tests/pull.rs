// SPDX-License-Identifier: GPL-3.0-only
use uuid::Uuid;
use zapime_crypto::{Binding, SecretKey};
use zapime_data::{sync::UploadOperation, Content, Record, Store};
use zapime_sync::{apply_encrypted_page, Change, Error, Page};

fn store(dir: &tempfile::TempDir, name: &str, profile: Uuid) -> Store {
    let mut s = Store::open(&dir.path().join(name)).unwrap();
    s.bind_sync_profile(profile).unwrap();
    s
}
fn favorite(id: Uuid, revision: u64, text: &str) -> Record {
    Record {
        id,
        revision,
        device_id: Uuid::new_v4(),
        deleted: false,
        content: Content::Favorite {
            title: "合成资料".into(),
            text: text.into(),
        },
    }
}
fn encrypted(
    profile: Uuid,
    key_id: Uuid,
    key: &SecretKey,
    record: Record,
    operation_id: Uuid,
    base: u64,
    revision: u64,
) -> Change {
    let binding = Binding {
        profile_id: profile,
        record_id: record.id,
        operation_id,
        key_id,
        kind: record.content.kind(),
    };
    let envelope = serde_json::from_slice(
        &zapime_crypto::seal(key, &binding, &serde_json::to_vec(&record).unwrap()).unwrap(),
    )
    .unwrap();
    Change {
        account_id: profile,
        device_id: Uuid::new_v4(),
        revision,
        operation: UploadOperation {
            operation_id,
            record_id: record.id,
            key_id,
            kind: record.content.kind(),
            base_revision: base,
            deleted: record.deleted,
            envelope,
        },
    }
}
fn change(
    profile: Uuid,
    key_id: Uuid,
    key: &SecretKey,
    record: Record,
    base: u64,
    revision: u64,
) -> Change {
    encrypted(profile, key_id, key, record, Uuid::new_v4(), base, revision)
}
fn page(after: u64, changes: Vec<Change>, head: u64) -> Vec<u8> {
    let next = changes.last().map(|c| c.revision).unwrap_or(after);
    serde_json::to_vec(&Page {
        changes,
        next_cursor: next,
        head,
    })
    .unwrap()
}
fn apply(
    s: &mut Store,
    profile: Uuid,
    after: u64,
    bytes: &[u8],
    key_id: Uuid,
    key: &SecretKey,
) -> Result<zapime_data::pull::ApplyReport, Error> {
    apply_encrypted_page(s, profile, after, bytes, |id| (id == key_id).then_some(key))
}
fn edit(s: &mut Store, id: Uuid, revision: u64, text: &str) {
    let device = s.device_id().unwrap();
    s.edit(
        Uuid::new_v4(),
        id,
        revision,
        device,
        Content::Favorite {
            title: "合成资料".into(),
            text: text.into(),
        },
        false,
    )
    .unwrap();
}
fn staged(s: &mut Store, profile: Uuid, key_id: Uuid, key: &SecretKey, revision: u64) -> Change {
    let pending = s.pending_operations(profile, 1).unwrap().remove(0);
    let mut change = encrypted(
        profile,
        key_id,
        key,
        pending.record,
        pending.operation_id,
        pending.server_revision,
        revision,
    );
    change.operation = s.stage_upload(profile, change.operation).unwrap();
    change
}
fn receipts(dir: &tempfile::TempDir, name: &str) -> i64 {
    rusqlite::Connection::open(dir.path().join(name))
        .unwrap()
        .query_row("SELECT count(*) FROM sync_received", [], |r| r.get(0))
        .unwrap()
}

#[test]
fn two_databases_exchange_real_authenticated_snapshots_without_echo_uploads() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut a = store(&dir, "a.db", p);
    let mut b = store(&dir, "b.db", p);
    for revision in 0..100 {
        edit(&mut a, id, revision, &format!("合成版本 {revision}"));
    }
    a.coalesce_unstaged(p, 4096).unwrap();
    let one = staged(&mut a, p, kid, &key, 1);
    let first = apply(&mut b, p, 0, &page(0, vec![one.clone()], 1), kid, &key).unwrap();
    assert_eq!(first.applied, 1);
    assert_eq!(first.cursor, 1);
    assert_eq!(b.get(id).unwrap().unwrap().revision, 1);
    assert_eq!(b.pending_count().unwrap(), 0);
    edit(&mut b, id, 1, "第二设备合成内容");
    let two = staged(&mut b, p, kid, &key, 2);
    assert_eq!(two.operation.base_revision, 1);
    let report = apply(&mut a, p, 0, &page(0, vec![one, two.clone()], 2), kid, &key).unwrap();
    assert_eq!((report.echoed, report.applied, report.cursor), (1, 1, 2));
    assert_eq!(a.get(id).unwrap().unwrap().revision, 101);
    assert_eq!(a.pending_count().unwrap(), 0);
    let report = apply(&mut b, p, 1, &page(1, vec![two], 2), kid, &key).unwrap();
    assert_eq!(report.echoed, 1);
    assert_eq!(b.get(id).unwrap().unwrap().revision, 2);
    assert_eq!(b.pending_count().unwrap(), 0);
    drop(a);
    let mut a = store(&dir, "a.db", p);
    assert_eq!(a.sync_cursor(p).unwrap(), 2);
    assert_eq!(receipts(&dir, "a.db"), 2);
    assert_eq!(
        a.get(id).unwrap().unwrap().content,
        b.get(id).unwrap().unwrap().content
    );
}

#[test]
fn a_bad_second_cipher_or_missing_key_never_partially_applies_the_first() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let mut s = store(&dir, "a.db", p);
    let one = change(p, kid, &key, favorite(Uuid::new_v4(), 1, "first"), 0, 1);
    let two = change(p, kid, &key, favorite(Uuid::new_v4(), 1, "second"), 0, 2);
    let mut bad = two.clone();
    let first = bad.operation.envelope.ciphertext.remove(0);
    bad.operation
        .envelope
        .ciphertext
        .insert(0, if first == 'A' { 'B' } else { 'A' });
    assert!(apply(&mut s, p, 0, &page(0, vec![one.clone(), bad], 2), kid, &key).is_err());
    assert!(s.records(true).unwrap().is_empty());
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
    assert_eq!(receipts(&dir, "a.db"), 0);
    let second_key = SecretKey::generate().unwrap();
    let other = Uuid::new_v4();
    let two_other = change(
        p,
        other,
        &second_key,
        favorite(Uuid::new_v4(), 1, "other key"),
        0,
        2,
    );
    assert!(matches!(
        apply(
            &mut s,
            p,
            0,
            &page(0, vec![one.clone(), two_other], 2),
            kid,
            &key
        ),
        Err(Error::KeyUnavailable)
    ));
    assert!(s.records(true).unwrap().is_empty());
    apply(&mut s, p, 0, &page(0, vec![one, two], 2), kid, &key).unwrap();
    assert_eq!(s.records(true).unwrap().len(), 2);
}

#[test]
fn binding_tampering_and_outer_delete_forgery_are_rejected_before_writes() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let mut s = store(&dir, "a.db", p);
    let original = change(
        p,
        kid,
        &key,
        favorite(Uuid::new_v4(), 1, "secret synthetic"),
        0,
        1,
    );
    for field in ["record_id", "operation_id", "kind", "deleted", "key_id"] {
        let mut bad = original.clone();
        match field {
            "record_id" => bad.operation.record_id = Uuid::new_v4(),
            "operation_id" => bad.operation.operation_id = Uuid::new_v4(),
            "kind" => bad.operation.kind = 3,
            "deleted" => bad.operation.deleted = true,
            _ => bad.operation.key_id = Uuid::new_v4(),
        };
        assert!(
            apply_encrypted_page(&mut s, p, 0, &page(0, vec![bad], 1), |_| Some(&key)).is_err()
        );
        assert!(s.records(true).unwrap().is_empty());
        assert_eq!(s.sync_cursor(p).unwrap(), 0);
    }
    let other_profile = Uuid::new_v4();
    let mut other = store(&dir, "b.db", other_profile);
    let mut alias = original;
    alias.account_id = other_profile;
    assert!(matches!(
        apply(
            &mut other,
            other_profile,
            0,
            &page(0, vec![alias], 1),
            kid,
            &key
        ),
        Err(Error::Authentication)
    ));
}

#[test]
fn own_echo_acknowledges_only_the_frozen_request_and_preserves_later_edits() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut s = store(&dir, "a.db", p);
    edit(&mut s, id, 0, "sent snapshot");
    let one = staged(&mut s, p, kid, &key, 1);
    edit(&mut s, id, 1, "later offline edit");
    let report = apply(&mut s, p, 0, &page(0, vec![one], 1), kid, &key).unwrap();
    assert_eq!(report.echoed, 1);
    assert_eq!(s.get(id).unwrap().unwrap().revision, 2);
    assert_eq!(s.pending_count().unwrap(), 1);
    assert_eq!(s.pending_operations(p, 1).unwrap()[0].server_revision, 1);
    let two = staged(&mut s, p, kid, &key, 2);
    s.acknowledge_upload(p, two.operation.operation_id, 2)
        .unwrap();
    apply(&mut s, p, 1, &page(1, vec![two], 2), kid, &key).unwrap();
    assert_eq!(s.get(id).unwrap().unwrap().revision, 2);
}

#[test]
fn acknowledging_ahead_of_pull_does_not_regress_server_mapping_or_local_record() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut s = store(&dir, "a.db", p);
    edit(&mut s, id, 0, "one");
    let one = staged(&mut s, p, kid, &key, 1);
    s.acknowledge_upload(p, one.operation.operation_id, 1)
        .unwrap();
    edit(&mut s, id, 1, "two");
    let two = staged(&mut s, p, kid, &key, 2);
    s.acknowledge_upload(p, two.operation.operation_id, 2)
        .unwrap();
    let report = apply(&mut s, p, 0, &page(0, vec![one, two], 2), kid, &key).unwrap();
    assert_eq!(report.echoed, 2);
    assert_eq!(s.get(id).unwrap().unwrap().revision, 2);
    edit(&mut s, id, 2, "three");
    assert_eq!(s.pending_operations(p, 1).unwrap()[0].server_revision, 2);
}

#[test]
fn local_pending_conflict_rolls_back_the_whole_page_and_keeps_frozen_bytes() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut s = store(&dir, "a.db", p);
    edit(&mut s, id, 0, "local offline");
    let frozen = staged(&mut s, p, kid, &key, 1).operation;
    let other_id = Uuid::new_v4();
    let one = change(
        p,
        kid,
        &key,
        favorite(other_id, 1, "should roll back"),
        0,
        1,
    );
    let two = change(p, kid, &key, favorite(id, 77, "foreign conflict"), 0, 2);
    assert!(matches!(
        apply(&mut s, p, 0, &page(0, vec![one, two], 2), kid, &key),
        Err(Error::Data(zapime_data::Error::SyncLocalConflict))
    ));
    assert!(s.get(other_id).unwrap().is_none());
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
    assert_eq!(receipts(&dir, "a.db"), 0);
    assert_eq!(s.pending_count().unwrap(), 1);
    assert_eq!(
        s.stage_upload(p, frozen.clone())
            .unwrap()
            .to_wire()
            .unwrap(),
        frozen.to_wire().unwrap()
    );
}

#[test]
fn untracked_restored_data_requires_matching_content_before_adopting_remote_version() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut original = store(&dir, "original.db", p);
    edit(&mut original, id, 0, "restored local");
    let archive = original.export_plaintext().unwrap();
    let mut s = store(&dir, "restored.db", p);
    s.restore_plaintext(&archive).unwrap();
    let bad = change(p, kid, &key, favorite(id, 99, "different remote"), 0, 1);
    assert!(matches!(
        apply(&mut s, p, 0, &page(0, vec![bad], 1), kid, &key),
        Err(Error::Data(zapime_data::Error::SyncLocalConflict))
    ));
    let same = change(p, kid, &key, favorite(id, 99, "restored local"), 0, 1);
    let report = apply(&mut s, p, 0, &page(0, vec![same], 1), kid, &key).unwrap();
    assert_eq!(report.unchanged, 1);
    assert_eq!(s.get(id).unwrap().unwrap().revision, 1);
    assert_eq!(s.pending_count().unwrap(), 0);
}

#[test]
fn history_gaps_head_jumps_unknown_fields_and_bounded_pages_never_advance_cursor() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let mut s = store(&dir, "a.db", p);
    let one = change(p, kid, &key, favorite(Uuid::new_v4(), 1, "one"), 0, 1);
    let valid = page(0, vec![one.clone()], 1);
    for (path, value) in [
        ("next_cursor", serde_json::json!(2)),
        ("head", serde_json::json!(0)),
        ("head", serde_json::json!(-1)),
        ("extra", serde_json::json!(true)),
    ] {
        let mut bad: serde_json::Value = serde_json::from_slice(&valid).unwrap();
        bad[path] = value;
        assert!(apply(&mut s, p, 0, &serde_json::to_vec(&bad).unwrap(), kid, &key).is_err());
    }
    assert!(apply(&mut s, p, 0, &page(0, vec![], 1), kid, &key).is_err());
    let mut skipped = one.clone();
    skipped.revision = 2;
    assert!(apply(&mut s, p, 0, &page(0, vec![skipped], 2), kid, &key).is_err());
    let many = (1..=101)
        .map(|revision| {
            let mut c = one.clone();
            c.revision = revision;
            c
        })
        .collect();
    assert!(apply(&mut s, p, 0, &page(0, many, 101), kid, &key).is_err());
    let oversized = vec![b' '; 10 * 1024 * 1024 + 1];
    assert!(apply(&mut s, p, 0, &oversized, kid, &key).is_err());
    let mut trailing = valid.clone();
    trailing.extend_from_slice(b" {}");
    assert!(apply(&mut s, p, 0, &trailing, kid, &key).is_err());
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
    assert!(s.records(true).unwrap().is_empty());
}

#[test]
fn tombstones_cannot_be_resurrected_by_renumbering_an_old_authenticated_operation() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut s = store(&dir, "a.db", p);
    let record = favorite(id, 1, "original");
    let one = change(p, kid, &key, record.clone(), 0, 1);
    let mut deleted = record.clone();
    deleted.deleted = true;
    deleted.revision = 2;
    let two = change(p, kid, &key, deleted, 1, 2);
    apply(&mut s, p, 0, &page(0, vec![one.clone(), two], 2), kid, &key).unwrap();
    drop(s);
    let mut s = store(&dir, "a.db", p);
    let mut replay = one;
    replay.revision = 3;
    replay.operation.base_revision = 2;
    assert!(apply(&mut s, p, 2, &page(2, vec![replay], 3), kid, &key).is_err());
    assert!(s.get(id).unwrap().unwrap().deleted);
    assert_eq!(s.sync_cursor(p).unwrap(), 2);
    let restore = change(p, kid, &key, record, 2, 3);
    apply(&mut s, p, 2, &page(2, vec![restore], 3), kid, &key).unwrap();
    assert!(!s.get(id).unwrap().unwrap().deleted);
    assert_eq!(s.get(id).unwrap().unwrap().revision, 3);
}

#[test]
fn stale_completion_and_wrong_profile_do_not_overwrite_edits_or_move_cursor() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut s = store(&dir, "a.db", p);
    let bytes = page(
        0,
        vec![change(p, kid, &key, favorite(id, 1, "remote"), 0, 1)],
        1,
    );
    let mut parallel = store(&dir, "a.db", p);
    apply(&mut s, p, 0, &bytes, kid, &key).unwrap();
    edit(&mut parallel, id, 1, "later local");
    assert!(matches!(
        apply(&mut s, p, 0, &bytes, kid, &key),
        Err(Error::Data(zapime_data::Error::SyncCursor))
    ));
    assert_eq!(s.get(id).unwrap().unwrap().revision, 2);
    assert_eq!(s.pending_count().unwrap(), 1);
    let q = Uuid::new_v4();
    let other = page(
        0,
        vec![change(
            q,
            kid,
            &key,
            favorite(Uuid::new_v4(), 1, "wrong account"),
            0,
            1,
        )],
        1,
    );
    assert!(matches!(
        apply(&mut s, q, 0, &other, kid, &key),
        Err(Error::Data(zapime_data::Error::SyncProfile))
    ));
    assert_eq!(s.sync_cursor(p).unwrap(), 1);
}

#[test]
fn semantic_candidate_collisions_rollback_and_ordered_delete_frees_a_slot() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let mut s = store(&dir, "a.db", p);
    let mut a = favorite(Uuid::new_v4(), 1, "unused");
    a.content = Content::CandidateRule {
        scheme: "pinyin".into(),
        code: "ni".into(),
        text: "你".into(),
        position: 1,
    };
    let mut b = a.clone();
    b.id = Uuid::new_v4();
    if let Content::CandidateRule { text, .. } = &mut b.content {
        *text = "拟".into();
    }
    let one = change(p, kid, &key, a.clone(), 0, 1);
    let bad = change(p, kid, &key, b.clone(), 0, 2);
    assert!(matches!(
        apply(&mut s, p, 0, &page(0, vec![one.clone(), bad], 2), kid, &key),
        Err(Error::Data(zapime_data::Error::PositionConflict))
    ));
    assert!(s.records(true).unwrap().is_empty());
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
    if let Content::CandidateRule { position, .. } = &mut b.content {
        *position = 2;
    }
    let two = change(p, kid, &key, b, 0, 2);
    apply(&mut s, p, 0, &page(0, vec![one, two], 2), kid, &key).unwrap();
    let mut deletion = a.clone();
    deletion.deleted = true;
    deletion.revision = 2;
    let delete = change(p, kid, &key, deletion, 1, 3);
    a.id = Uuid::new_v4();
    let replacement = change(p, kid, &key, a, 0, 4);
    apply(
        &mut s,
        p,
        2,
        &page(2, vec![delete, replacement], 4),
        kid,
        &key,
    )
    .unwrap();
    assert_eq!(s.records(false).unwrap().len(), 2);
    assert_eq!(s.pending_count().unwrap(), 0);
}

#[test]
fn a_later_page_conflict_also_rolls_back_its_earlier_upload_acknowledgement() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut s = store(&dir, "a.db", p);
    edit(&mut s, id, 0, "original upload");
    let own = staged(&mut s, p, kid, &key, 1);
    edit(&mut s, id, 1, "later local edit");
    let remote = change(p, kid, &key, favorite(id, 10, "other device"), 1, 2);
    assert!(matches!(
        apply(
            &mut s,
            p,
            0,
            &page(0, vec![own.clone(), remote], 2),
            kid,
            &key
        ),
        Err(Error::Data(zapime_data::Error::SyncLocalConflict))
    ));
    assert_eq!(s.pending_count().unwrap(), 2);
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
    assert_eq!(receipts(&dir, "a.db"), 0);
    assert_eq!(s.pending_operations(p, 1).unwrap()[0].server_revision, 0);
    assert_eq!(
        s.stage_upload(p, own.operation.clone())
            .unwrap()
            .to_wire()
            .unwrap(),
        own.operation.to_wire().unwrap()
    );
    s.acknowledge_upload(p, own.operation.operation_id, 1)
        .unwrap();
    assert_eq!(s.pending_count().unwrap(), 1);
}

#[test]
fn even_authenticated_replacement_ciphertext_cannot_ack_a_different_frozen_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let p = Uuid::new_v4();
    let kid = Uuid::new_v4();
    let key = SecretKey::generate().unwrap();
    let id = Uuid::new_v4();
    let mut s = store(&dir, "a.db", p);
    edit(&mut s, id, 0, "frozen original");
    let own = staged(&mut s, p, kid, &key, 1);
    let replacement = encrypted(
        p,
        kid,
        &key,
        favorite(id, 1, "replacement"),
        own.operation.operation_id,
        0,
        1,
    );
    assert!(matches!(
        apply(&mut s, p, 0, &page(0, vec![replacement], 1), kid, &key),
        Err(Error::Data(zapime_data::Error::SyncHistory))
    ));
    assert_eq!(s.pending_count().unwrap(), 1);
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
    assert_eq!(receipts(&dir, "a.db"), 0);
    assert_eq!(
        s.stage_upload(p, own.operation.clone())
            .unwrap()
            .to_wire()
            .unwrap(),
        own.operation.to_wire().unwrap()
    );
}
