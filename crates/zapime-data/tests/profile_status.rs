// SPDX-License-Identifier: GPL-3.0-only
use rusqlite::Connection;
use uuid::Uuid;
use zapime_data::{sync::inspect_profile, Content, Store};

#[test]
fn inspection_never_initializes_and_preserves_existing_personal_history() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("missing/data.sqlite3");
    let empty = inspect_profile(&path).unwrap();
    assert!(!empty.initialized);
    assert_eq!(empty.account_id, None);
    assert!(!path.parent().unwrap().exists());

    let path = dir.path().join("data.sqlite3");
    let mut store = Store::open(&path).unwrap();
    let device = store.device_id().unwrap();
    let account = Uuid::new_v4();
    assert_eq!(inspect_profile(&path).unwrap().account_id, None);
    store
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            device,
            Content::Favorite {
                title: "synthetic".into(),
                text: "local-only fixture".into(),
            },
            false,
        )
        .unwrap();
    store.bind_sync_profile(account).unwrap();
    let before = store.export_plaintext().unwrap();
    let pending = store.pending_count().unwrap();
    for _ in 0..3 {
        let state = inspect_profile(&path).unwrap();
        assert!(state.initialized);
        assert_eq!(state.account_id, Some(account));
    }
    assert_eq!(store.export_plaintext().unwrap(), before);
    assert_eq!(store.pending_count().unwrap(), pending);
    assert_eq!(store.sync_cursor(account).unwrap(), 0);
    assert!(store.bind_sync_profile(Uuid::new_v4()).is_err());
    drop(store);
    assert_eq!(inspect_profile(&path).unwrap().account_id, Some(account));
}

#[test]
fn malformed_binding_and_future_schema_are_errors_not_unbound() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.sqlite3");
    drop(Store::open(&path).unwrap());
    let raw = Connection::open(&path).unwrap();
    for invalid in [
        "",
        "not-an-account",
        "00000000-0000-0000-0000-000000000000",
        "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
        "aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa",
    ] {
        raw.execute(
            "INSERT OR REPLACE INTO metadata VALUES('sync_profile',?1)",
            [invalid],
        )
        .unwrap();
        assert!(inspect_profile(&path).is_err(), "{invalid}");
    }
    raw.execute("DELETE FROM metadata WHERE key='sync_profile'", [])
        .unwrap();
    raw.execute_batch("PRAGMA user_version=3;").unwrap();
    assert!(inspect_profile(&path).is_err());
    assert_eq!(
        raw.query_row::<u32, _, _>("PRAGMA user_version", [], |r| r.get(0))
            .unwrap(),
        3
    );
    drop(raw);
    let corrupt = dir.path().join("corrupt.sqlite3");
    std::fs::write(&corrupt, b"synthetic invalid database").unwrap();
    assert!(inspect_profile(&corrupt).is_err());
    assert_eq!(
        std::fs::read(&corrupt).unwrap(),
        b"synthetic invalid database"
    );
    assert!(inspect_profile(dir.path()).is_err());
}
