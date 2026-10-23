// SPDX-License-Identifier: GPL-3.0-only
use uuid::Uuid;
use zapime_data::{
    quarantine::{FailureKind, MAX_PAGE_BYTES},
    Content, Error, Store,
};

#[test]
fn journal_byte_quota_does_not_evict_and_corruption_cannot_fake_deduplication() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("quota.db");
    let p = Uuid::new_v4();
    let mut s = Store::open(&path).unwrap();
    s.bind_sync_profile(p).unwrap();
    // Storage boundary accepts already shape-validated wire from the sync layer.
    // This large synthetic fixture checks bytes independently from the 8-page cap.
    let padded = " ".repeat(9 * 1024 * 1024);
    let mut ids = Vec::new();
    for suffix in ["0", "1", "2"] {
        ids.push(
            s.retain_failed_pull(p, 0, FailureKind::MissingKey, &format!("{padded}{suffix}"))
                .unwrap()
                .id,
        );
    }
    assert!(matches!(
        s.retain_failed_pull(p, 0, FailureKind::MissingKey, &format!("{padded}3")),
        Err(Error::RetainedFull)
    ));
    assert_eq!(s.retained_pulls(p, 0, 20).unwrap().len(), 3);
    assert!(s
        .retain_failed_pull(
            p,
            0,
            FailureKind::MissingKey,
            &"x".repeat(MAX_PAGE_BYTES + 1)
        )
        .is_err());
    assert!(matches!(
        s.retain_failed_pull(p, 1, FailureKind::MissingKey, "{}"),
        Err(Error::SyncCursor)
    ));
    assert!(matches!(
        s.retain_failed_pull(Uuid::new_v4(), 0, FailureKind::MissingKey, "{}"),
        Err(Error::SyncProfile)
    ));
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute(
        "UPDATE sync_retained_pulls SET wire='damaged' WHERE id=?1",
        [&ids[0]],
    )
    .unwrap();
    assert!(matches!(s.retained_pull(p, &ids[0]), Err(Error::Integrity)));
    assert!(matches!(
        s.retain_failed_pull(p, 0, FailureKind::MissingKey, &format!("{padded}0")),
        Err(Error::Integrity)
    ));
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
}
#[test]
fn additive_v2_table_preparation_preserves_existing_records_and_pending_history() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.db");
    let p = Uuid::new_v4();
    let mut s = Store::open(&path).unwrap();
    s.bind_sync_profile(p).unwrap();
    let id = Uuid::new_v4();
    let device = s.device_id().unwrap();
    let local = s
        .edit(
            Uuid::new_v4(),
            id,
            0,
            device,
            Content::Favorite {
                title: "合成".into(),
                text: "offline".into(),
            },
            false,
        )
        .unwrap();
    drop(s);
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute_batch("DROP TABLE sync_retained_pulls;").unwrap();
    drop(db);
    let mut s = Store::open(&path).unwrap();
    assert_eq!(s.get(id).unwrap(), Some(local));
    assert_eq!(s.pending_count().unwrap(), 1);
    assert_eq!(s.sync_cursor(p).unwrap(), 0);
    assert!(s.retained_pulls(p, 0, 20).unwrap().is_empty());
    assert!(s.retained_pulls(p, 0, 0).is_err());
    assert!(s.retained_pulls(p, 0, 21).is_err());
    assert!(s.retained_pull(p, "bad-id").is_err());
}
