// SPDX-License-Identifier: GPL-3.0-only
use rusqlite::Connection;
use std::path::Path;
use uuid::Uuid;
use zapime_data::{
    inventory::{InventoryState, MAX_INVENTORY_ITEMS},
    sync::WorkspaceBinding,
    Content, Error, Store,
};

fn binding() -> WorkspaceBinding {
    WorkspaceBinding {
        profile_id: Uuid::new_v4(),
        key_id: Uuid::new_v4(),
        material_sha256: [7; 32],
    }
}

fn edit(store: &mut Store, id: Uuid, revision: u64, deleted: bool) {
    store
        .edit(
            Uuid::new_v4(),
            id,
            revision,
            Uuid::new_v4(),
            Content::Favorite {
                title: "Synthetic inventory".into(),
                text: "Synthetic body must not enter preview output".into(),
            },
            deleted,
        )
        .unwrap();
}

fn snapshot(path: &Path) -> Vec<String> {
    let db = Connection::open(path).unwrap();
    let mut result = Vec::new();
    for table in [
        "records",
        "metadata",
        "operations",
        "sync_queue",
        "sync_uploads",
        "sync_versions",
        "sync_received",
        "sync_retained_pulls",
        "sqlite_schema",
    ] {
        let mut statement = db
            .prepare(&format!("SELECT * FROM {table} ORDER BY 1"))
            .unwrap();
        let count = statement.column_count();
        for row in statement
            .query_map([], |row| {
                let mut values = Vec::new();
                for column in 0..count {
                    values.push(match row.get_ref(column)? {
                        rusqlite::types::ValueRef::Null => "null".to_owned(),
                        rusqlite::types::ValueRef::Integer(value) => value.to_string(),
                        rusqlite::types::ValueRef::Text(value) => {
                            String::from_utf8(value.to_vec()).unwrap()
                        }
                        _ => panic!("unexpected synthetic column"),
                    });
                }
                Ok(values.join("|"))
            })
            .unwrap()
        {
            result.push(format!("{table}:{}", row.unwrap()));
        }
    }
    result
}

#[test]
fn restored_inventory_is_paged_metadata_only_and_never_enqueues_tombstones() {
    let dir = tempfile::tempdir().unwrap();
    let mut original = Store::open(&dir.path().join("original.db")).unwrap();
    for index in 1..=307 {
        edit(&mut original, Uuid::from_u128(index), 0, index % 5 == 0);
    }
    let archive = original.export_plaintext().unwrap();
    let path = dir.path().join("restored.db");
    let mut restored = Store::open(&path).unwrap();
    assert_eq!(restored.restore_unbound_plaintext(&archive).unwrap(), 307);
    let expected = binding();
    restored.prepare_sync_workspace(&expected).unwrap();
    let before = snapshot(&path);
    let mut reader = Store::open_panel_reader(&path).unwrap();
    let mut after = None;
    let mut ids = Vec::new();
    let mut tombstones = 0;
    loop {
        let page = reader.sync_inventory_page(&expected, after, 7).unwrap();
        assert!(page.items.len() <= 7);
        assert!(!serde_json::to_string(&page)
            .unwrap()
            .contains("Synthetic body"));
        for entry in &page.items {
            assert_eq!(entry.state, InventoryState::Untracked);
            assert!(!entry.has_local_operation_history);
            assert!(!entry.has_pending_local_operation);
            assert!(!entry.current_snapshot_has_local_operation);
            assert!(!entry.current_snapshot_pending);
            assert!(entry.known_server_revision.is_none());
            assert_eq!(entry.local_revision, 1);
            assert_eq!(entry.kind, 4);
            ids.push(entry.id);
            tombstones += usize::from(entry.deleted);
        }
        if page.next_after.is_none() {
            break;
        }
        assert_eq!(page.next_after, page.items.last().map(|entry| entry.id));
        after = page.next_after;
    }
    assert_eq!(ids, (1..=307).map(Uuid::from_u128).collect::<Vec<_>>());
    assert_eq!(tombstones, 61);
    assert_eq!(restored.pending_count().unwrap(), 0);
    assert_eq!(restored.export_plaintext().unwrap(), archive);
    assert_eq!(snapshot(&path), before);
}

#[test]
fn preview_checks_full_binding_limits_and_existing_schema_without_writes() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("bound.db");
    let mut store = Store::open(&path).unwrap();
    let expected = binding();
    store.prepare_sync_workspace(&expected).unwrap();
    edit(&mut store, Uuid::new_v4(), 0, false);
    let before = snapshot(&path);
    for fault in 0..3 {
        let mut wrong = expected.clone();
        match fault {
            0 => wrong.profile_id = Uuid::new_v4(),
            1 => wrong.key_id = Uuid::new_v4(),
            _ => wrong.material_sha256[0] ^= 1,
        }
        assert!(matches!(
            store.sync_inventory_page(&wrong, None, 1),
            Err(Error::SyncWorkspace)
        ));
    }
    for limit in [0, MAX_INVENTORY_ITEMS + 1, usize::MAX] {
        assert!(matches!(
            store.sync_inventory_page(&expected, None, limit),
            Err(Error::Invalid)
        ));
    }
    assert!(matches!(
        store.sync_inventory_page(&expected, Some(Uuid::nil()), 1),
        Err(Error::Invalid)
    ));
    let page = store
        .sync_inventory_page(&expected, None, MAX_INVENTORY_ITEMS)
        .unwrap();
    assert_eq!(page.items[0].state, InventoryState::PendingLocalOperation);
    assert!(page.items[0].current_snapshot_pending);
    assert_eq!(snapshot(&path), before);
    let raw = Connection::open(&path).unwrap();
    raw.execute_batch("DROP INDEX sync_pending_record").unwrap();
    let before = snapshot(&path);
    let mut reader = Store::open_panel_reader(&path).unwrap();
    assert!(reader.sync_inventory_page(&expected, None, 1).is_err());
    assert_eq!(snapshot(&path), before);
}

#[test]
fn old_local_history_cannot_claim_the_current_snapshot_is_pending() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old-history.db");
    let mut store = Store::open(&path).unwrap();
    let expected = binding();
    store.prepare_sync_workspace(&expected).unwrap();
    let id = Uuid::new_v4();
    edit(&mut store, id, 0, false);
    // Represent an imported newer revision without rewriting old operation history.
    let mut newer = store.get(id).unwrap().unwrap();
    newer.revision = 2;
    if let Content::Favorite { text, .. } = &mut newer.content {
        *text = "new synthetic snapshot".into();
    }
    let raw = Connection::open(&path).unwrap();
    raw.execute(
        "UPDATE records SET revision=2,body=?1",
        [serde_json::to_string(&newer).unwrap()],
    )
    .unwrap();
    let before = snapshot(&path);
    let page = store.sync_inventory_page(&expected, None, 1).unwrap();
    let item = &page.items[0];
    assert_eq!(item.local_revision, 2);
    assert!(item.has_local_operation_history && item.has_pending_local_operation);
    assert!(!item.current_snapshot_has_local_operation && !item.current_snapshot_pending);
    assert_eq!(snapshot(&path), before);
}

#[test]
fn oversized_operation_history_is_not_materialized_or_mistaken_for_current_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("large-history.db");
    let mut store = Store::open(&path).unwrap();
    let expected = binding();
    store.prepare_sync_workspace(&expected).unwrap();
    edit(&mut store, Uuid::new_v4(), 0, false);
    let raw = Connection::open(&path).unwrap();
    raw.execute(
        "UPDATE operations SET result=json_set(result,'$.padding',?1)",
        [" ".repeat(1_048_577)],
    )
    .unwrap();
    let before = snapshot(&path);
    let page = store.sync_inventory_page(&expected, None, 1).unwrap();
    let item = &page.items[0];
    assert!(item.has_local_operation_history && item.has_pending_local_operation);
    assert!(!item.current_snapshot_has_local_operation && !item.current_snapshot_pending);
    assert_eq!(snapshot(&path), before);
}

#[test]
fn malformed_matching_operation_or_record_metadata_rejects_without_partial_results() {
    let dir = tempfile::tempdir().unwrap();
    for fault in [
        "pending",
        "operation-id",
        "revision",
        "body-id",
        "deleted",
        "kind",
        "oversize",
    ] {
        let path = dir.path().join(format!("bad-{fault}.db"));
        let mut store = Store::open(&path).unwrap();
        let expected = binding();
        store.prepare_sync_workspace(&expected).unwrap();
        edit(&mut store, Uuid::new_v4(), 0, false);
        let raw = Connection::open(&path).unwrap();
        match fault {
            "pending" => {
                raw.execute("UPDATE operations SET pending=2", []).unwrap();
            }
            "operation-id" => {
                raw.execute(
                    "UPDATE operations SET result=json_set(result,'$.id','malformed')",
                    [],
                )
                .unwrap();
            }
            "revision" => {
                raw.execute("UPDATE records SET body=json_set(body,'$.revision',2)", [])
                    .unwrap();
            }
            "body-id" => {
                raw.execute(
                    "UPDATE records SET body=json_set(body,'$.id',?1)",
                    [Uuid::new_v4().to_string()],
                )
                .unwrap();
            }
            "deleted" => {
                raw.execute("UPDATE records SET body=json_set(body,'$.deleted',0)", [])
                    .unwrap();
            }
            "kind" => {
                raw.execute(
                    "UPDATE records SET body=json_set(body,'$.content.type','unknown')",
                    [],
                )
                .unwrap();
            }
            _ => {
                raw.execute(
                    "UPDATE records SET body=json_set(body,'$.padding',?1)",
                    [" ".repeat(1_048_577)],
                )
                .unwrap();
            }
        }
        let before = snapshot(&path);
        assert!(
            store.sync_inventory_page(&expected, None, 1).is_err(),
            "{fault}"
        );
        assert_eq!(snapshot(&path), before, "{fault}");
    }
}

#[test]
fn inventory_sql_work_is_interruptible_and_cleanup_allows_retry() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("budget.db");
    let mut store = Store::open(&path).unwrap();
    let expected = binding();
    store.prepare_sync_workspace(&expected).unwrap();
    edit(&mut store, Uuid::new_v4(), 0, false);
    let raw = Connection::open(&path).unwrap();
    let before = snapshot(&path);
    raw.execute_batch("ALTER TABLE records RENAME TO inventory_actual_records;
        CREATE VIEW records AS SELECT * FROM inventory_actual_records WHERE
        (SELECT sum(x) FROM (WITH RECURSIVE work(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM work WHERE x<100000000) SELECT x FROM work))>=0;").unwrap();
    let schema = || {
        raw.query_row::<String, _, _>(
        "SELECT group_concat(COALESCE(sql,''),char(10)) FROM (SELECT sql FROM sqlite_schema ORDER BY name)",
        [], |row| row.get(0),
    ).unwrap()
    };
    let before_preview_schema = schema();
    let error = store.sync_inventory_page(&expected, None, 1).err().unwrap();
    match error {
        Error::Storage(rusqlite::Error::SqliteFailure(code, _)) => {
            assert_eq!(code.code, rusqlite::ErrorCode::OperationInterrupted)
        }
        _ => panic!("expected actual SQLite VM interruption"),
    }
    assert_eq!(schema(), before_preview_schema);
    raw.execute_batch("DROP VIEW records; ALTER TABLE inventory_actual_records RENAME TO records;")
        .unwrap();
    assert_eq!(
        store
            .sync_inventory_page(&expected, None, 1)
            .unwrap()
            .items
            .len(),
        1
    );
    // Our fixture's ALTER TABLE roundtrip normalizes quoted identifiers in
    // sqlite_schema. Compare data separately from that intentional DDL; the
    // exact schema surrounding the preview itself was checked above.
    let data = |rows: Vec<String>| {
        rows.into_iter()
            .filter(|row| !row.starts_with("sqlite_schema:"))
            .collect::<Vec<_>>()
    };
    assert_eq!(data(snapshot(&path)), data(before));
}
