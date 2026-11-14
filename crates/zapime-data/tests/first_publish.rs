// SPDX-License-Identifier: GPL-3.0-only
//! Real SQLite records-only recovery, immutable first-publish queue and rollback.
use rusqlite::Connection;
use uuid::Uuid;
use zapime_data::{
    first_publish::{FirstPublishSelection, MAX_FIRST_PUBLISH_SELECTION},
    inventory::InventoryState,
    sync::WorkspaceBinding,
    Content, Error, Store,
};

fn binding() -> WorkspaceBinding {
    WorkspaceBinding {
        profile_id: Uuid::new_v4(),
        key_id: Uuid::new_v4(),
        material_sha256: [17; 32],
    }
}
fn favorite(store: &mut Store, id: Uuid, deleted: bool) {
    store
        .edit(
            Uuid::new_v4(),
            id,
            0,
            Uuid::new_v4(),
            Content::Favorite {
                title: "Synthetic title".into(),
                text: "Synthetic first-publish body".into(),
            },
            deleted,
        )
        .unwrap();
}
fn restored() -> (tempfile::TempDir, Store, WorkspaceBinding, Uuid, Uuid, Uuid) {
    let temp = tempfile::tempdir().unwrap();
    let mut source = Store::open(&temp.path().join("source.db")).unwrap();
    let a = Uuid::from_u128(1);
    let b = Uuid::from_u128(2);
    let tombstone = Uuid::from_u128(3);
    favorite(&mut source, a, false);
    favorite(&mut source, b, false);
    favorite(&mut source, tombstone, true);
    let archive = source.export_plaintext().unwrap();
    let mut local = Store::open(&temp.path().join("local.db")).unwrap();
    assert_eq!(local.restore_unbound_plaintext(&archive).unwrap(), 3);
    assert_eq!(local.pending_count().unwrap(), 0);
    let binding = binding();
    local.prepare_sync_workspace(&binding).unwrap();
    (temp, local, binding, a, b, tombstone)
}
fn choose(store: &mut Store, binding: &WorkspaceBinding, id: Uuid) -> FirstPublishSelection {
    store
        .inspect_first_publish(binding, 0, id, Uuid::new_v4())
        .unwrap()
}

#[test]
fn explicit_first_upload_preserves_records_and_is_atomic_and_idempotent() {
    let (_temp, mut store, binding, a, b, tomb) = restored();
    let old_export = store.export_plaintext().unwrap();
    let first = choose(&mut store, &binding, a);
    let second = choose(&mut store, &binding, b);
    assert_eq!(first.local_revision, 1);
    assert_ne!(first.body_sha256, [0; 32]);
    assert!(matches!(
        store.inspect_first_publish(&binding, 0, tomb, Uuid::new_v4()),
        Err(Error::SyncLocalConflict)
    ));
    assert!(matches!(
        store.enqueue_first_publish(&binding, 0, &[first.clone()], false),
        Err(Error::Invalid)
    ));
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(store.export_plaintext().unwrap(), old_export);
    assert_eq!(
        store.enqueue_first_publish(&binding, 0, &[first.clone(), second.clone()], true)
            .unwrap(),
        2
    );
    assert_eq!(store.pending_count().unwrap(), 2);
    assert_eq!(store.export_plaintext().unwrap(), old_export);
    let page = store.sync_inventory_page(&binding, None, 3).unwrap();
    assert_eq!(page.items.len(), 3);
    assert_eq!(page.items[0].state, InventoryState::PendingLocalOperation);
    assert!(page.items[0].current_snapshot_pending);
    assert_eq!(page.items[1].state, InventoryState::PendingLocalOperation);
    assert_eq!(page.items[2].state, InventoryState::Untracked);
    assert!(page.items[2].deleted);
    // A lost UI ACK cannot duplicate an operation or change queue order.
    assert_eq!(
        store.enqueue_first_publish(&binding, 0, &[first.clone(), second.clone()], true)
            .unwrap(),
        0
    );
    assert_eq!(store.pending_count().unwrap(), 2);
    assert!(matches!(
        store.inspect_first_publish(&binding, 0, a, Uuid::new_v4()),
        Err(Error::SyncLocalConflict)
    ));
}

#[test]
fn duplicate_reused_stale_and_mismatched_inputs_never_partially_seed() {
    let (temp, mut store, binding, a, b, tomb) = restored();
    let first = choose(&mut store, &binding, a);
    let second = choose(&mut store, &binding, b);
    let before = store.export_plaintext().unwrap();
    let mut corrupt = second.clone();
    corrupt.body_sha256[0] ^= 1;
    assert!(matches!(
        store.enqueue_first_publish(&binding, 0, &[first.clone(), corrupt], true),
        Err(Error::Conflict)
    ));
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(before, store.export_plaintext().unwrap());
    for bad in [
        vec![first.clone(), first.clone()],
        vec![first.clone(), FirstPublishSelection {
            operation_id: first.operation_id, ..second.clone()
        }],
        vec![],
        vec![first.clone(); MAX_FIRST_PUBLISH_SELECTION + 1],
    ] {
        assert!(matches!(
            store.enqueue_first_publish(&binding, 0, &bad, true),
            Err(Error::Invalid)
        ));
    }
    assert!(matches!(
        store.enqueue_first_publish(&binding, 1, &[first.clone()], true),
        Err(Error::SyncCursor)
    ));
    let mut foreign = binding.clone();
    foreign.material_sha256[0] ^= 1;
    assert!(matches!(
        store.enqueue_first_publish(&foreign, 0, &[first.clone()], true),
        Err(Error::SyncWorkspace)
    ));
    let mut old = first.clone();
    old.local_revision = 2;
    assert!(matches!(
        store.enqueue_first_publish(&binding, 0, &[old], true),
        Err(Error::Conflict)
    ));
    let _ = tomb;
    let db = Connection::open(temp.path().join("local.db")).unwrap();
    db.execute(
        "INSERT INTO sync_versions VALUES(?1,?2)",
        rusqlite::params![a.to_string(), 3],
    )
    .unwrap();
    assert!(matches!(
        store.enqueue_first_publish(&binding, 0, &[first], true),
        Err(Error::SyncLocalConflict)
    ));
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(before, store.export_plaintext().unwrap());
}

#[test]
fn edit_history_and_same_operation_id_are_not_first_publication_authority() {
    let (_temp, mut store, binding, a, b, _) = restored();
    let approved = choose(&mut store, &binding, a);
    store
        .edit(
            Uuid::new_v4(),
            b,
            1,
            Uuid::new_v4(),
            Content::Favorite {
                title: "Synthetic title".into(),
                text: "A later local edit".into(),
            },
            false,
        )
        .unwrap();
    assert!(matches!(
        store.inspect_first_publish(&binding, 0, b, Uuid::new_v4()),
        Err(Error::SyncLocalConflict)
    ));
    let same = FirstPublishSelection {
        record_id: b,
        ..approved.clone()
    };
    assert!(store
        .enqueue_first_publish(&binding, 0, &[same], true)
        .is_err());
    assert_eq!(
        store.enqueue_first_publish(&binding, 0, &[approved], true)
            .unwrap(),
        1
    );
    // One old regular pending edit remains unchanged and is never reclassified.
    assert_eq!(store.pending_count().unwrap(), 2);
}
