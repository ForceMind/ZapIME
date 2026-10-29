// SPDX-License-Identifier: GPL-3.0-only
use std::path::Path;
use uuid::Uuid;
use zapime_core::local_backup;
use zapime_data::{Content, Store};
fn initialize(root: &Path) -> Store {
    std::fs::create_dir_all(root.join("data")).unwrap();
    Store::open(&root.join("data/personal.sqlite3")).unwrap()
}
#[test]
fn encrypted_snapshot_recovers_exact_records_after_source_changes() {
    let dir = tempfile::tempdir().unwrap();
    let source_root = dir.path().join("source");
    let mut source = initialize(&source_root);
    let record = Uuid::new_v4();
    let device = Uuid::new_v4();
    source
        .edit(
            Uuid::new_v4(),
            record,
            0,
            device,
            Content::Favorite {
                title: "synthetic".into(),
                text: "本机加密备份测试 ☃".into(),
            },
            false,
        )
        .unwrap();
    let expected = source.export_plaintext().unwrap();
    let backup = local_backup::create(&source_root).unwrap();
    assert_eq!(backup.code.len(), 43);
    assert!(!String::from_utf8_lossy(&backup.package).contains("本机加密备份测试"));
    source
        .edit(
            Uuid::new_v4(),
            record,
            1,
            device,
            Content::Favorite {
                title: "synthetic".into(),
                text: "newer offline edit".into(),
            },
            false,
        )
        .unwrap();
    let target_root = dir.path().join("target");
    let target = initialize(&target_root);
    let empty = target.export_plaintext().unwrap();
    assert!(local_backup::restore(&target_root, &[b'A'; 43], &backup.package).is_err());
    assert_eq!(target.export_plaintext().unwrap(), empty);
    let mut tampered = backup.package.clone();
    let n = tampered.len() / 2;
    tampered[n] = if tampered[n] == b'A' { b'B' } else { b'A' };
    assert!(local_backup::restore(&target_root, &backup.code, &tampered).is_err());
    assert_eq!(target.export_plaintext().unwrap(), empty);
    assert_eq!(
        local_backup::restore(&target_root, &backup.code, &backup.package).unwrap(),
        1
    );
    assert_eq!(target.export_plaintext().unwrap(), expected);
    assert_ne!(source.export_plaintext().unwrap(), expected);
    assert_eq!(
        local_backup::restore(&target_root, &backup.code, &backup.package).unwrap_err(),
        "restore_requires_fresh_local_profile"
    );
    assert_eq!(target.export_plaintext().unwrap(), expected);
}
#[test]
fn generic_recovery_cannot_be_misread_as_a_local_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let target = initialize(dir.path());
    let before = target.export_plaintext().unwrap();
    let key = zapime_crypto::SecretKey::generate().unwrap();
    let (code, package) = zapime_crypto::create_recovery(&key, &before).unwrap();
    assert_eq!(
        local_backup::restore(dir.path(), code.expose().as_bytes(), &package).unwrap_err(),
        "invalid_or_unauthenticated_backup"
    );
    assert_eq!(target.export_plaintext().unwrap(), before);
}
#[test]
fn authenticated_restore_refuses_bound_profile_and_missing_database() {
    let dir = tempfile::tempdir().unwrap();
    let source_root = dir.path().join("source");
    let source = initialize(&source_root);
    let backup = local_backup::create(&source_root).unwrap();
    let target_root = dir.path().join("bound");
    let mut bound = initialize(&target_root);
    let account = Uuid::new_v4();
    bound.bind_sync_profile(account).unwrap();
    assert_eq!(
        local_backup::restore(&target_root, &backup.code, &backup.package).unwrap_err(),
        "restore_requires_fresh_local_profile"
    );
    assert_eq!(bound.sync_profile().unwrap(), Some(account));
    assert_eq!(bound.sync_cursor(account).unwrap(), 0);
    let missing = dir.path().join("not-created");
    assert!(local_backup::restore(&missing, &backup.code, &backup.package).is_err());
    assert!(!missing.exists());
    assert!(local_backup::create(&missing).is_err());
    assert!(local_backup::restore(&target_root, &[0; 42], &backup.package).is_err());
    assert!(local_backup::restore(
        &target_root,
        &backup.code,
        &vec![0; local_backup::MAX_PACKAGE + 1]
    )
    .is_err());
    assert!(source.records(true).unwrap().is_empty());
}
