// SPDX-License-Identifier: GPL-3.0-only
use rusqlite::Connection;
use std::path::Path;
use uuid::Uuid;
use zapime_crypto::{Binding, SecretKey};
use zapime_data::{sync::UploadOperation, Content, Store};
use zapime_sync::workspace::{self, Material};

fn local(path: &Path) -> Store {
    let mut store = Store::open(path).unwrap();
    let device = store.device_id().unwrap();
    store
        .edit(
            Uuid::new_v4(),
            Uuid::new_v4(),
            0,
            device,
            Content::Favorite {
                title: "synthetic workspace".into(),
                text: "本机内容保持原样".into(),
            },
            false,
        )
        .unwrap();
    store
}

fn snapshot(path: &Path) -> Vec<(String, Vec<Vec<String>>)> {
    let db = Connection::open(path).unwrap();
    [
        "records",
        "operations",
        "sync_queue",
        "sync_uploads",
        "sync_versions",
        "sync_received",
        "sync_retained_pulls",
    ]
    .into_iter()
    .map(|table| {
        let mut statement = db
            .prepare(&format!("SELECT * FROM {table} ORDER BY 1"))
            .unwrap();
        let columns = statement.column_count();
        let rows = statement
            .query_map([], |row| {
                (0..columns)
                    .map(|column| {
                        row.get_ref(column).map(|value| match value {
                            rusqlite::types::ValueRef::Null => "NULL".to_owned(),
                            rusqlite::types::ValueRef::Integer(value) => value.to_string(),
                            rusqlite::types::ValueRef::Text(value) => {
                                std::str::from_utf8(value).unwrap().to_owned()
                            }
                            _ => panic!("unexpected synthetic column type"),
                        })
                    })
                    .collect::<Result<Vec<_>, _>>()
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        (table.to_owned(), rows)
    })
    .collect()
}

#[test]
fn protected_format_is_fixed_bounded_and_account_scoped() {
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let encoded = material.encode();
    assert_eq!(encoded.len(), workspace::MATERIAL_BYTES);
    assert_eq!(&encoded[..8], b"ZSKM\0\0\0\x01");
    assert_eq!(&encoded[8..24], profile.as_bytes());
    assert_eq!(&encoded[24..40], material.key_id.as_bytes());
    let decoded = Material::decode(profile, &encoded).unwrap();
    assert_eq!(decoded.encode().as_slice(), encoded.as_slice());
    assert_eq!(decoded.key_id, material.key_id);
    assert!(Material::generate(Uuid::nil()).is_err());
    assert!(Material::decode(Uuid::new_v4(), &encoded).is_err());
    assert!(Material::decode(Uuid::nil(), &encoded).is_err());
    for size in [
        0,
        7,
        workspace::MATERIAL_BYTES - 1,
        workspace::MATERIAL_BYTES + 1,
    ] {
        assert!(Material::decode(profile, &vec![0; size]).is_err());
    }
    for range in [0..8, 8..24, 24..40] {
        let mut malformed = encoded.clone();
        malformed[range].fill(0);
        assert!(Material::decode(profile, &malformed).is_err());
    }
}

#[test]
fn key_only_recovery_authenticates_purpose_account_key_and_root() {
    let profile = Uuid::new_v4();
    let original = Material::generate(profile).unwrap();
    let (code, package) = workspace::create_recovery(&original).unwrap();
    assert_eq!(code.expose().len(), 43);
    assert!(package.len() < workspace::MAX_RECOVERY_PACKAGE);
    let restored =
        workspace::restore_recovery(profile, code.expose().as_bytes(), &package).unwrap();
    assert_eq!(restored.encode().as_slice(), original.encode().as_slice());
    assert!(
        workspace::restore_recovery(Uuid::new_v4(), code.expose().as_bytes(), &package).is_err()
    );
    let (wrong, _) = workspace::create_recovery(&Material::generate(profile).unwrap()).unwrap();
    assert!(workspace::restore_recovery(profile, wrong.expose().as_bytes(), &package).is_err());
    let mut tampered: serde_json::Value = serde_json::from_slice(&package).unwrap();
    let encrypted = tampered["ciphertext"].as_str().unwrap();
    tampered["ciphertext"] = format!(
        "{}{}",
        if encrypted.starts_with('A') { "B" } else { "A" },
        &encrypted[1..]
    )
    .into();
    assert!(workspace::restore_recovery(
        profile,
        code.expose().as_bytes(),
        &serde_json::to_vec(&tampered).unwrap()
    )
    .is_err());
    // The same generic crypto envelope under a different application purpose
    // (including local-records backups) is never accepted as a sync key package.
    let key = SecretKey::generate().unwrap();
    for wrong_payload in [
        b"ZapIME/local-records/v1\0synthetic archive".as_slice(),
        b"synthetic generic recovery".as_slice(),
        b"ZapIME/sync-material/v1\0truncated metadata".as_slice(),
    ] {
        let (wrong_code, wrong_package) =
            zapime_crypto::create_recovery(&key, wrong_payload).unwrap();
        assert!(workspace::restore_recovery(
            profile,
            wrong_code.expose().as_bytes(),
            &wrong_package
        )
        .is_err());
    }
    assert!(workspace::restore_recovery(profile, &[0; 42], &package).is_err());
    assert!(workspace::restore_recovery(profile, code.expose().as_bytes(), &[]).is_err());
    assert!(workspace::restore_recovery(
        profile,
        code.expose().as_bytes(),
        &vec![0; workspace::MAX_RECOVERY_PACKAGE + 1]
    )
    .is_err());
}

#[test]
fn inspection_and_preparation_never_create_or_migrate() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("missing/personal.sqlite3");
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let state = workspace::inspect(&path, profile, None).unwrap();
    assert!(!state.initialized && !state.material_present && !state.prepared);
    assert!(state.account_id.is_none() && state.key_id.is_none());
    assert!(!path.parent().unwrap().exists());
    let state = workspace::inspect(&path, profile, Some(&material)).unwrap();
    assert!(!state.initialized && state.material_present && !state.prepared);
    assert!(workspace::prepare(&path, profile, &material).is_err());
    assert!(!path.parent().unwrap().exists());
    assert!(workspace::inspect(dir.path(), profile, None).is_err());

    let path = dir.path().join("future.sqlite3");
    drop(local(&path));
    let raw = Connection::open(&path).unwrap();
    raw.execute_batch("PRAGMA user_version=3").unwrap();
    let before = snapshot(&path);
    assert!(workspace::inspect(&path, profile, None).is_err());
    assert!(workspace::prepare(&path, profile, &material).is_err());
    assert_eq!(snapshot(&path), before);
    assert_eq!(
        raw.query_row::<u32, _, _>("PRAGMA user_version", [], |row| row.get(0))
            .unwrap(),
        3
    );
    let malformed = dir.path().join("corrupt.sqlite3");
    std::fs::write(&malformed, b"synthetic invalid database").unwrap();
    assert!(workspace::inspect(&malformed, profile, None).is_err());
    assert!(workspace::prepare(&malformed, profile, &material).is_err());
    assert_eq!(
        std::fs::read(&malformed).unwrap(),
        b"synthetic invalid database"
    );
}

#[test]
fn explicit_prepare_only_binds_metadata_and_is_idempotent() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("personal.sqlite3");
    let store = local(&path);
    let before = snapshot(&path);
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let before_status = workspace::inspect(&path, profile, Some(&material)).unwrap();
    assert!(before_status.initialized && before_status.material_present && !before_status.prepared);
    let ready = workspace::prepare(&path, profile, &material).unwrap();
    assert!(ready.prepared);
    assert_eq!(ready.account_id, Some(profile));
    assert_eq!(ready.key_id, Some(material.key_id));
    assert_eq!(
        workspace::prepare(&path, profile, &material).unwrap(),
        ready
    );
    assert_eq!(snapshot(&path), before);
    assert_eq!(store.sync_profile().unwrap(), Some(profile));
    let without_material = workspace::inspect(&path, profile, None).unwrap();
    assert!(
        without_material.initialized
            && !without_material.material_present
            && !without_material.prepared
    );
    assert_eq!(without_material.key_id, Some(material.key_id));
    let visible = serde_json::to_value(&ready).unwrap();
    assert_eq!(visible.as_object().unwrap().len(), 5);
    for field in [
        "initialized",
        "account_id",
        "key_id",
        "material_present",
        "prepared",
    ] {
        assert!(visible.get(field).is_some());
    }
    assert!(visible.get("root").is_none());
    assert!(workspace::inspect(&path, Uuid::new_v4(), None).is_err());
    assert!(workspace::prepare(&path, Uuid::new_v4(), &material).is_err());
    let other_key = Material::generate(profile).unwrap();
    assert!(workspace::prepare(&path, profile, &other_key).is_err());
    assert!(workspace::inspect(&path, profile, Some(&other_key)).is_err());
    // Same public IDs do not authorize replacing the actual root bytes.
    let mut changed_root = material.encode();
    changed_root[40] ^= 1;
    let changed = Material::decode(profile, &changed_root).unwrap();
    assert_eq!(changed.key_id, material.key_id);
    assert!(workspace::prepare(&path, profile, &changed).is_err());
    assert!(workspace::inspect(&path, profile, Some(&changed)).is_err());
    assert_eq!(snapshot(&path), before);
}

#[test]
fn orphaned_vault_material_survives_atomic_database_failure_and_retry() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("personal.sqlite3");
    drop(local(&path));
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    // Memory fixture represents bytes already committed/read back by the
    // platform vault. This is not an Android Keystore or process-kill test.
    let vault = material.encode();
    let raw = Connection::open(&path).unwrap();
    raw.execute_batch("CREATE TRIGGER reject_workspace BEFORE INSERT ON metadata WHEN NEW.key='sync_material_sha256' BEGIN SELECT RAISE(ABORT,'synthetic write failure'); END").unwrap();
    let before = snapshot(&path);
    assert!(workspace::prepare(&path, profile, &material).is_err());
    assert_eq!(
        raw.query_row::<i64, _, _>(
            "SELECT count(*) FROM metadata WHERE substr(key,1,5)='sync_'",
            [],
            |row| row.get(0)
        )
        .unwrap(),
        0
    );
    assert_eq!(snapshot(&path), before);
    raw.execute_batch("DROP TRIGGER reject_workspace").unwrap();
    let resumed = Material::decode(profile, &vault).unwrap();
    assert!(
        workspace::inspect(&path, profile, Some(&resumed))
            .unwrap()
            .material_present
    );
    assert!(
        workspace::prepare(&path, profile, &resumed)
            .unwrap()
            .prepared
    );
    assert_eq!(resumed.encode().as_slice(), vault.as_slice());
    assert_eq!(snapshot(&path), before);
}

#[test]
fn cooperative_sqlite_budget_interrupts_and_rolls_back_partial_preparation() {
    use sha2::{Digest, Sha256};
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("personal.sqlite3");
    drop(local(&path));
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let binding = zapime_data::sync::WorkspaceBinding {
        profile_id: profile,
        key_id: material.key_id,
        material_sha256: Sha256::digest(material.encode().as_slice()).into(),
    };
    let raw = Connection::open(&path).unwrap();
    // Synthetic slow SQL, not a slow-disk or handset timing measurement.
    // Interrupt the final insert after the earlier metadata inserts happened.
    raw.execute_batch("CREATE TRIGGER slow_workspace BEFORE INSERT ON metadata WHEN NEW.key='sync_material_sha256' BEGIN SELECT sum(x) FROM (WITH RECURSIVE work(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM work WHERE x<100000000) SELECT x FROM work); END").unwrap();
    let before = snapshot(&path);
    let mut writer = Store::open_backup_writer(&path).unwrap();
    let error = writer.prepare_sync_workspace(&binding).err().unwrap();
    match error {
        zapime_data::Error::Storage(rusqlite::Error::SqliteFailure(sqlite, _)) => {
            assert_eq!(sqlite.code, rusqlite::ErrorCode::OperationInterrupted);
        }
        _ => panic!("expected an actual SQLite VM interruption"),
    }
    assert_eq!(
        raw.query_row::<i64, _, _>(
            "SELECT count(*) FROM metadata WHERE substr(key,1,5)='sync_'",
            [],
            |row| row.get(0)
        )
        .unwrap(),
        0
    );
    assert_eq!(snapshot(&path), before);
    raw.execute_batch("DROP TRIGGER slow_workspace").unwrap();
    assert!(
        workspace::prepare(&path, profile, &material)
            .unwrap()
            .prepared
    );
}

#[test]
fn incomplete_legacy_binding_and_unbound_sync_history_fail_closed() {
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let queries = [
        "INSERT INTO metadata VALUES('sync_cursor','0')".to_owned(),
        format!("INSERT INTO metadata VALUES('sync_profile','{profile}')"),
        "INSERT INTO metadata VALUES('sync_unknown_legacy','0')".to_owned(),
        "UPDATE operations SET pending=0".to_owned(),
        format!("INSERT INTO sync_versions VALUES('{}',1)", Uuid::new_v4()),
        "INSERT INTO sync_uploads SELECT id,'{}',NULL FROM operations LIMIT 1".to_owned(),
        format!("INSERT INTO sync_received VALUES('{}',1,'{}')", Uuid::new_v4(), "a".repeat(64)),
        format!("INSERT INTO sync_retained_pulls(id,profile_id,after_cursor,reason,wire) VALUES('{}','{profile}',0,'missing_key','{{}}')", "a".repeat(64)),
    ];
    for query in queries {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("personal.sqlite3");
        drop(local(&path));
        let raw = Connection::open(&path).unwrap();
        raw.execute_batch(&query).unwrap();
        let before = snapshot(&path);
        assert!(
            workspace::inspect(&path, profile, Some(&material)).is_err(),
            "{query}"
        );
        assert!(
            workspace::prepare(&path, profile, &material).is_err(),
            "{query}"
        );
        assert_eq!(snapshot(&path), before);
    }
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("bound.sqlite3");
    let mut store = local(&path);
    store.bind_sync_profile(profile).unwrap();
    assert!(workspace::inspect(&path, profile, None).is_err());
    assert!(workspace::prepare(&path, profile, &material).is_err());
    assert_eq!(store.sync_profile().unwrap(), Some(profile));
    assert_eq!(store.sync_cursor(profile).unwrap(), 0);
}

#[test]
fn malformed_prepared_metadata_is_not_repaired_or_replaced() {
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let edits = [
        "UPDATE metadata SET value='bad-profile' WHERE key='sync_profile'",
        "UPDATE metadata SET value='00000000-0000-0000-0000-000000000000' WHERE key='sync_key_id'",
        "UPDATE metadata SET value='AA' WHERE key='sync_material_sha256'",
        "UPDATE metadata SET value=printf('%0130d',1) WHERE key='sync_material_sha256'",
        "UPDATE metadata SET value='-1' WHERE key='sync_cursor'",
        "UPDATE metadata SET value='01' WHERE key='sync_cursor'",
        "INSERT INTO metadata VALUES('sync_legacy','0')",
        "DELETE FROM metadata WHERE key='sync_key_id'",
    ];
    for edit in edits {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("personal.sqlite3");
        drop(local(&path));
        workspace::prepare(&path, profile, &material).unwrap();
        let raw = Connection::open(&path).unwrap();
        raw.execute_batch(edit).unwrap();
        let metadata: Vec<(String, String)> = raw
            .prepare("SELECT key,value FROM metadata ORDER BY key")
            .unwrap()
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        assert!(
            workspace::inspect(&path, profile, Some(&material)).is_err(),
            "{edit}"
        );
        assert!(
            workspace::prepare(&path, profile, &material).is_err(),
            "{edit}"
        );
        assert_eq!(
            raw.prepare("SELECT key,value FROM metadata ORDER BY key")
                .unwrap()
                .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
                .unwrap()
                .collect::<Result<Vec<(String, String)>, _>>()
                .unwrap(),
            metadata
        );
    }
}

#[test]
fn retry_with_identical_material_preserves_frozen_uploads_receipts_and_cursor() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("personal.sqlite3");
    let mut store = local(&path);
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    workspace::prepare(&path, profile, &material).unwrap();
    let bytes = material.encode();
    let key = SecretKey::from_protected_storage(&bytes[40..]).unwrap();
    let pending = store.pending_operations(profile, 1).unwrap().pop().unwrap();
    let binding = Binding {
        profile_id: profile,
        record_id: pending.record.id,
        operation_id: pending.operation_id,
        key_id: material.key_id,
        kind: pending.record.content.kind(),
    };
    let operation = UploadOperation {
        operation_id: pending.operation_id,
        record_id: pending.record.id,
        key_id: material.key_id,
        kind: binding.kind,
        base_revision: pending.server_revision,
        deleted: pending.record.deleted,
        envelope: serde_json::from_slice(
            &zapime_crypto::seal(
                &key,
                &binding,
                &serde_json::to_vec(&pending.record).unwrap(),
            )
            .unwrap(),
        )
        .unwrap(),
    };
    let frozen = store.stage_upload(profile, operation).unwrap();
    let before = snapshot(&path);
    assert!(
        workspace::prepare(&path, profile, &material)
            .unwrap()
            .prepared
    );
    assert_eq!(snapshot(&path), before);
    store
        .acknowledge_upload(profile, pending.operation_id, 1)
        .unwrap();
    let page = serde_json::json!({"changes":[{"account_id":profile,"device_id":Uuid::new_v4(),"revision":1,"operation":frozen}],"next_cursor":1,"head":1});
    zapime_sync::apply_encrypted_page(
        &mut store,
        profile,
        0,
        &serde_json::to_vec(&page).unwrap(),
        |id| (id == material.key_id).then_some(&key),
    )
    .unwrap();
    assert_eq!(store.sync_cursor(profile).unwrap(), 1);
    let before = snapshot(&path);
    assert!(
        workspace::prepare(&path, profile, &material)
            .unwrap()
            .prepared
    );
    assert_eq!(snapshot(&path), before);
    assert_eq!(store.sync_cursor(profile).unwrap(), 1);
    assert_eq!(store.pending_count().unwrap(), 0);
    assert!(
        workspace::inspect(&path, profile, Some(&material))
            .unwrap()
            .prepared
    );
}

#[cfg(unix)]
#[test]
fn symlink_database_is_rejected_without_touching_target() {
    use std::os::unix::fs::symlink;
    let dir = tempfile::tempdir().unwrap();
    let target = dir.path().join("actual.sqlite3");
    drop(local(&target));
    let link = dir.path().join("alias.sqlite3");
    symlink(&target, &link).unwrap();
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let before = snapshot(&target);
    assert!(workspace::inspect(&link, profile, None).is_err());
    assert!(workspace::prepare(&link, profile, &material).is_err());
    assert_eq!(snapshot(&target), before);
}
