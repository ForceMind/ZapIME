// SPDX-License-Identifier: GPL-3.0-only
use uuid::Uuid;
use zapime_data::{Content, Store};

fn edit(store: &mut Store) {
    store
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            Uuid::new_v4(),
            Content::Favorite {
                title: "synthetic".into(),
                text: "本机备份 ☃".into(),
            },
            false,
        )
        .unwrap();
}
#[test]
fn snapshot_matches_open_format_and_preserves_history() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("source.db");
    let mut source = Store::open(&path).unwrap();
    edit(&mut source);
    let archive = source.export_plaintext().unwrap();
    let pending = source.pending_count().unwrap();
    let mut reader = Store::open_panel_reader(&path).unwrap();
    assert_eq!(reader.export_backup_snapshot(1024 * 1024).unwrap(), archive);
    assert!(reader.export_backup_snapshot(512).is_err());
    assert_eq!(source.export_plaintext().unwrap(), archive);
    assert_eq!(source.pending_count().unwrap(), pending);
    let mut fresh = Store::open(&dir.path().join("fresh.db")).unwrap();
    assert_eq!(fresh.restore_unbound_plaintext(&archive).unwrap(), 1);
    assert_eq!(fresh.export_plaintext().unwrap(), archive);
    assert!(fresh.restore_unbound_plaintext(&archive).is_err());
}
#[test]
fn empty_records_do_not_allow_reusing_bound_or_historical_profile() {
    let dir = tempfile::tempdir().unwrap();
    let mut source = Store::open(&dir.path().join("source.db")).unwrap();
    edit(&mut source);
    let archive = source.export_plaintext().unwrap();
    let bound_path = dir.path().join("bound.db");
    let mut bound = Store::open(&bound_path).unwrap();
    let account = Uuid::new_v4();
    bound.bind_sync_profile(account).unwrap();
    assert!(bound.restore_unbound_plaintext(&archive).is_err());
    assert!(bound.records(true).unwrap().is_empty());
    assert_eq!(bound.sync_profile().unwrap(), Some(account));
    assert_eq!(bound.sync_cursor(account).unwrap(), 0);
    let historical_path = dir.path().join("historical.db");
    let mut historical = Store::open(&historical_path).unwrap();
    edit(&mut historical);
    let raw = rusqlite::Connection::open(&historical_path).unwrap();
    raw.execute("DELETE FROM records", []).unwrap();
    let pending = historical.pending_count().unwrap();
    assert!(historical.restore_unbound_plaintext(&archive).is_err());
    assert!(historical.records(true).unwrap().is_empty());
    assert_eq!(historical.pending_count().unwrap(), pending);
}
#[test]
fn backup_writer_never_initializes_or_downgrades() {
    let dir = tempfile::tempdir().unwrap();
    let missing = dir.path().join("missing.db");
    assert!(Store::open_backup_writer(&missing).is_err());
    assert!(!missing.exists());
    let path = dir.path().join("future.db");
    let raw = rusqlite::Connection::open(&path).unwrap();
    raw.execute_batch("PRAGMA user_version=3;").unwrap();
    assert!(Store::open_backup_writer(&path).is_err());
    assert_eq!(
        raw.query_row::<u32, _, _>("PRAGMA user_version", [], |r| r.get(0))
            .unwrap(),
        3
    );
}
