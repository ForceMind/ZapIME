// SPDX-License-Identifier: GPL-3.0-only
use uuid::Uuid;
use zapime_data::{
    sync::{CipherEnvelope, UploadOperation},
    Content, Error, Store,
};

fn edit(store: &mut Store, id: Uuid, revision: u64, text: &str) -> Uuid {
    let operation = Uuid::new_v4();
    let device = store.device_id().unwrap();
    store
        .edit(
            operation,
            id,
            revision,
            device,
            Content::Favorite {
                title: "合成测试".into(),
                text: text.into(),
            },
            false,
        )
        .unwrap();
    operation
}
// 这里只验证持久状态，使用不含用户正文的形状合法占位密文；实际 AEAD 在独立服务灾备测试中验证。
fn packet(store: &mut Store, profile: Uuid) -> UploadOperation {
    let pending = store.pending_operations(profile, 1).unwrap().remove(0);
    UploadOperation {
        operation_id: pending.operation_id,
        record_id: pending.record.id,
        key_id: Uuid::new_v4(),
        kind: pending.record.content.kind(),
        base_revision: pending.server_revision,
        deleted: pending.record.deleted,
        envelope: CipherEnvelope {
            version: 1,
            nonce: "A".repeat(32),
            ciphertext: "A".repeat(22),
        },
    }
}

#[test]
fn coalescing_keeps_last_unstaged_per_record_and_preserves_history_and_replay() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.sqlite3");
    let profile = Uuid::new_v4();
    let mut store = Store::open(&path).unwrap();
    store.bind_sync_profile(profile).unwrap();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let first = edit(&mut store, a, 0, "first");
    let other = edit(&mut store, b, 0, "other");
    let last = edit(&mut store, a, 1, "latest");
    assert_eq!(store.coalesce_unstaged(profile, 4096).unwrap(), 1);
    assert_eq!(store.coalesce_unstaged(profile, 4096).unwrap(), 0);
    assert_eq!(store.pending_count().unwrap(), 2);
    let pending = store.pending_operations(profile, 20).unwrap();
    assert_eq!(
        pending.iter().map(|p| p.operation_id).collect::<Vec<_>>(),
        vec![other, last]
    );
    assert_eq!(pending[1].record.revision, 2);
    assert_eq!(pending[1].server_revision, 0);
    let device = store.device_id().unwrap();
    let replay = store
        .edit(
            first,
            a,
            0,
            device,
            Content::Favorite {
                title: "合成测试".into(),
                text: "first".into(),
            },
            false,
        )
        .unwrap();
    assert_eq!(replay.revision, 1);
    assert_eq!(store.get(a).unwrap().unwrap().revision, 2);
    assert_eq!(store.pending_count().unwrap(), 2);
    let raw = rusqlite::Connection::open(&path).unwrap();
    assert_eq!(
        raw.query_row::<i64, _, _>("SELECT count(*) FROM operations", [], |r| r.get(0))
            .unwrap(),
        3
    );
    let saved: String = raw
        .query_row(
            "SELECT result FROM operations WHERE id=?1",
            [first.to_string()],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(
        serde_json::from_str::<zapime_data::Record>(&saved)
            .unwrap()
            .content,
        replay.content
    );
    drop(store);
    let mut reopened = Store::open(&path).unwrap();
    assert_eq!(
        reopened
            .pending_operations(profile, 20)
            .unwrap()
            .iter()
            .map(|p| p.operation_id)
            .collect::<Vec<_>>(),
        vec![other, last]
    );
    assert_eq!(reopened.sync_cursor(profile).unwrap(), 0);
}

#[test]
fn coalescing_never_rewrites_or_skips_a_frozen_request() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.sqlite3")).unwrap();
    let profile = Uuid::new_v4();
    let id = Uuid::new_v4();
    store.bind_sync_profile(profile).unwrap();
    let frozen = edit(&mut store, id, 0, "frozen");
    let wire = packet(&mut store, profile);
    let saved = store.stage_upload(profile, wire).unwrap();
    edit(&mut store, id, 1, "middle");
    let latest = edit(&mut store, id, 2, "last");
    assert_eq!(store.coalesce_unstaged(profile, 4096).unwrap(), 1);
    assert_eq!(store.pending_count().unwrap(), 2);
    let mut replacement = packet(&mut store, profile);
    replacement.envelope.nonce = "B".repeat(32);
    replacement.key_id = Uuid::new_v4();
    assert_eq!(
        store
            .stage_upload(profile, replacement)
            .unwrap()
            .to_wire()
            .unwrap(),
        saved.to_wire().unwrap()
    );
    store.acknowledge_upload(profile, frozen, 23).unwrap();
    let pending = store.pending_operations(profile, 10).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].operation_id, latest);
    assert_eq!(pending[0].record.revision, 3);
    assert_eq!(pending[0].server_revision, 23);
    let upload = packet(&mut store, profile);
    store.stage_upload(profile, upload).unwrap();
    store.acknowledge_upload(profile, latest, 29).unwrap();
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(store.sync_cursor(profile).unwrap(), 0);
}

#[test]
fn coalescing_retains_the_last_delete_tombstone_and_does_not_restore_it_by_replay() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.sqlite3")).unwrap();
    let profile = Uuid::new_v4();
    let id = Uuid::new_v4();
    store.bind_sync_profile(profile).unwrap();
    let first = edit(&mut store, id, 0, "temporary");
    let device = store.device_id().unwrap();
    let content = store.get(id).unwrap().unwrap().content;
    let delete = Uuid::new_v4();
    store
        .edit(delete, id, 1, device, content.clone(), true)
        .unwrap();
    assert_eq!(store.coalesce_unstaged(profile, 4096).unwrap(), 1);
    let pending = store.pending_operations(profile, 10).unwrap();
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].operation_id, delete);
    assert!(pending[0].record.deleted);
    assert!(packet(&mut store, profile).deleted);
    store.edit(first, id, 0, device, content, false).unwrap();
    assert!(store.get(id).unwrap().unwrap().deleted);
    assert_eq!(store.pending_count().unwrap(), 1);
}

#[test]
fn coalescing_is_profile_checked_and_only_changes_the_bounded_window() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.sqlite3")).unwrap();
    let profile = Uuid::new_v4();
    let id = Uuid::new_v4();
    store.bind_sync_profile(profile).unwrap();
    let first = edit(&mut store, id, 0, "one");
    edit(&mut store, id, 1, "two");
    let last = edit(&mut store, id, 2, "three");
    assert!(matches!(
        store.coalesce_unstaged(Uuid::new_v4(), 4096),
        Err(Error::SyncProfile)
    ));
    for limit in [0, 4097] {
        assert!(matches!(
            store.coalesce_unstaged(profile, limit),
            Err(Error::Invalid)
        ));
    }
    assert_eq!(store.pending_count().unwrap(), 3);
    assert_eq!(store.coalesce_unstaged(profile, 1).unwrap(), 0);
    assert_eq!(store.coalesce_unstaged(profile, 2).unwrap(), 1);
    assert_eq!(store.pending_count().unwrap(), 2);
    assert!(matches!(
        store.acknowledge_upload(profile, first, 9),
        Err(Error::SyncAck)
    ));
    assert_eq!(store.coalesce_unstaged(profile, 2).unwrap(), 1);
    assert_eq!(
        store.pending_operations(profile, 10).unwrap()[0].operation_id,
        last
    );
}

#[test]
fn coalescing_after_a_snapshot_is_read_prevents_staging_that_superseded_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.sqlite3");
    let mut one = Store::open(&path).unwrap();
    let mut two = Store::open(&path).unwrap();
    let profile = Uuid::new_v4();
    let id = Uuid::new_v4();
    one.bind_sync_profile(profile).unwrap();
    edit(&mut one, id, 0, "old");
    let stale = packet(&mut one, profile);
    let last = edit(&mut two, id, 1, "new");
    two.coalesce_unstaged(profile, 4096).unwrap();
    assert!(matches!(
        one.stage_upload(profile, stale),
        Err(Error::SyncOrder)
    ));
    assert_eq!(
        one.pending_operations(profile, 1).unwrap()[0].operation_id,
        last
    );
}

#[test]
fn a_hundred_offline_edits_can_prepare_one_upload_without_equating_revisions() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.sqlite3")).unwrap();
    let profile = Uuid::new_v4();
    let id = Uuid::new_v4();
    store.bind_sync_profile(profile).unwrap();
    let mut last = Uuid::nil();
    for revision in 0..100 {
        last = edit(&mut store, id, revision, &format!("version {revision}"));
    }
    assert_eq!(store.pending_count().unwrap(), 100);
    assert_eq!(store.coalesce_unstaged(profile, 4096).unwrap(), 99);
    assert_eq!(store.pending_count().unwrap(), 1);
    let packet = packet(&mut store, profile);
    assert_eq!(packet.operation_id, last);
    assert_eq!(packet.base_revision, 0);
    store.stage_upload(profile, packet).unwrap();
    store.acknowledge_upload(profile, last, 1).unwrap();
    assert_eq!(store.get(id).unwrap().unwrap().revision, 100);
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(store.sync_cursor(profile).unwrap(), 0);
}

#[test]
fn crash_retry_keeps_exact_first_wire_nonce_and_key_version() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.sqlite3");
    let profile = Uuid::new_v4();
    let first = {
        let mut store = Store::open(&path).unwrap();
        store.bind_sync_profile(profile).unwrap();
        edit(&mut store, Uuid::new_v4(), 0, "尚未发送");
        let input = packet(&mut store, profile);
        store.stage_upload(profile, input).unwrap()
    };
    let mut reopened = Store::open(&path).unwrap();
    let mut retry = packet(&mut reopened, profile);
    retry.envelope.nonce = "B".repeat(32);
    retry.key_id = Uuid::new_v4();
    let saved = reopened.stage_upload(profile, retry).unwrap();
    assert_eq!(saved.to_wire().unwrap(), first.to_wire().unwrap());
    assert_eq!(reopened.pending_count().unwrap(), 1);
}

#[test]
fn acknowledgement_preserves_later_local_edit_and_does_not_jump_pull_cursor() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.db")).unwrap();
    let profile = Uuid::new_v4();
    let record = Uuid::new_v4();
    store.bind_sync_profile(profile).unwrap();
    let a = edit(&mut store, record, 0, "第一版");
    let upload = packet(&mut store, profile);
    store.stage_upload(profile, upload).unwrap();
    let b = edit(&mut store, record, 1, "第二版");
    store.acknowledge_upload(profile, a, 17).unwrap();
    assert_eq!(store.pending_count().unwrap(), 1);
    let pending = store.pending_operations(profile, 2).unwrap();
    assert_eq!(pending[0].operation_id, b);
    assert_eq!(pending[0].record.revision, 2);
    assert_eq!(pending[0].server_revision, 17);
    assert_eq!(
        store.get(record).unwrap().unwrap().content,
        Content::Favorite {
            title: "合成测试".into(),
            text: "第二版".into()
        }
    );
    assert_eq!(store.sync_cursor(profile).unwrap(), 0);
    let upload = packet(&mut store, profile);
    store.stage_upload(profile, upload).unwrap();
    store.acknowledge_upload(profile, b, 25).unwrap();
    store.acknowledge_upload(profile, a, 17).unwrap();
    assert!(matches!(
        store.acknowledge_upload(profile, a, 18),
        Err(Error::SyncAck)
    ));
    assert_eq!(store.pending_count().unwrap(), 0);
}

#[test]
fn profile_order_and_invalid_ack_rejections_are_atomic() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.db")).unwrap();
    let profile = Uuid::new_v4();
    store.bind_sync_profile(profile).unwrap();
    let a = edit(&mut store, Uuid::new_v4(), 0, "甲");
    let b = edit(&mut store, Uuid::new_v4(), 0, "乙");
    assert!(matches!(
        store.bind_sync_profile(Uuid::new_v4()),
        Err(Error::SyncProfile)
    ));
    let mut out_of_order = packet(&mut store, profile);
    out_of_order.operation_id = b;
    assert!(matches!(
        store.stage_upload(profile, out_of_order),
        Err(Error::SyncOrder)
    ));
    assert!(matches!(
        store.acknowledge_upload(profile, a, 17),
        Err(Error::SyncAck)
    ));
    let upload = packet(&mut store, profile);
    store.stage_upload(profile, upload).unwrap();
    assert!(matches!(
        store.acknowledge_upload(profile, a, 0),
        Err(Error::SyncAck)
    ));
    assert!(matches!(
        store.acknowledge_upload(Uuid::new_v4(), a, 17),
        Err(Error::SyncProfile)
    ));
    assert_eq!(store.pending_count().unwrap(), 2);
    assert_eq!(
        store.pending_operations(profile, 1).unwrap()[0].server_revision,
        0
    );
}

#[test]
fn pending_rows_are_immutable_operation_snapshots() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(&dir.path().join("data.db")).unwrap();
    let profile = Uuid::new_v4();
    store.bind_sync_profile(profile).unwrap();
    let record = Uuid::new_v4();
    edit(&mut store, record, 0, "旧正文");
    edit(&mut store, record, 1, "新正文");
    let pending = store.pending_operations(profile, 2).unwrap();
    assert_eq!(pending.len(), 2);
    assert_eq!(pending[0].record.revision, 1);
    assert_eq!(pending[1].record.revision, 2);
    let mut upload = packet(&mut store, profile);
    upload.kind = 3;
    assert!(matches!(
        store.stage_upload(profile, upload),
        Err(Error::Invalid)
    ));
    assert!(store.pending_operations(profile, 0).is_err());
    assert!(store.pending_operations(profile, 257).is_err());
}

#[test]
fn v1_database_migrates_without_losing_data_or_pending_operations() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.db");
    let record = Uuid::new_v4();
    {
        let mut store = Store::open(&path).unwrap();
        edit(&mut store, record, 0, "旧库保留");
    }
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute_batch("DROP TRIGGER queue_new_operation; DROP TABLE sync_queue; DROP TABLE sync_uploads; DROP TABLE sync_versions; PRAGMA user_version=1;")
        .unwrap();
    drop(db);
    let store = Store::open(&path).unwrap();
    assert_eq!(store.pending_count().unwrap(), 1);
    assert_eq!(store.get(record).unwrap().unwrap().revision, 1);
    drop(store);
    let db = rusqlite::Connection::open(&path).unwrap();
    assert_eq!(
        db.pragma_query_value(None, "user_version", |r| r.get::<_, u32>(0))
            .unwrap(),
        2
    );
    db.execute_batch("PRAGMA user_version=3;").unwrap();
    drop(db);
    assert!(matches!(Store::open(&path), Err(Error::Version)));
}

#[test]
fn corrupted_staged_metadata_cannot_acknowledge_a_different_record() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.db");
    let profile = Uuid::new_v4();
    let operation = {
        let mut store = Store::open(&path).unwrap();
        store.bind_sync_profile(profile).unwrap();
        let op = edit(&mut store, Uuid::new_v4(), 0, "保留待发");
        let input = packet(&mut store, profile);
        store.stage_upload(profile, input).unwrap();
        op
    };
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute(
        "UPDATE sync_uploads SET wire=json_set(wire,'$.record_id',?1)",
        [Uuid::new_v4().to_string()],
    )
    .unwrap();
    drop(db);
    let mut store = Store::open(&path).unwrap();
    let input = packet(&mut store, profile);
    assert!(matches!(
        store.stage_upload(profile, input),
        Err(Error::Invalid)
    ));
    assert!(matches!(
        store.acknowledge_upload(profile, operation, 17),
        Err(Error::Invalid)
    ));
    assert_eq!(store.pending_count().unwrap(), 1);
    assert_eq!(
        store.pending_operations(profile, 1).unwrap()[0].server_revision,
        0
    );
}

#[test]
fn explicit_queue_sequence_survives_vacuum_and_reopen() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.db");
    let profile = Uuid::new_v4();
    let ids = {
        let mut store = Store::open(&path).unwrap();
        store.bind_sync_profile(profile).unwrap();
        (0..5)
            .map(|_| edit(&mut store, Uuid::new_v4(), 0, "顺序保留"))
            .collect::<Vec<_>>()
    };
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute_batch("VACUUM;").unwrap();
    drop(db);
    for _ in 0..3 {
        drop(Store::open(&path).unwrap());
    }
    let db = rusqlite::Connection::open(&path).unwrap();
    assert_eq!(
        db.query_row(
            "SELECT seq FROM sqlite_sequence WHERE name='sync_queue'",
            [],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        5
    );
    drop(db);
    let mut reopened = Store::open(&path).unwrap();
    assert_eq!(
        reopened
            .pending_operations(profile, 10)
            .unwrap()
            .iter()
            .map(|op| op.operation_id)
            .collect::<Vec<_>>(),
        ids
    );
}
