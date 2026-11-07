// SPDX-License-Identifier: GPL-3.0-only
//! Real local AEAD/storage orchestration. The Change/Page objects simulate a
//! server protocol response; these are not HTTP, Android or Cloudflare results.
use rusqlite::Connection;
use std::path::Path;
use uuid::Uuid;
use zapime_crypto::{Binding, SecretKey};
use zapime_data::{sync::UploadOperation, Content, Store};
use zapime_sync::{
    client,
    workspace::{self, Descriptor, Material},
    Change, Retention,
};

fn new_store(path: &Path, profile: Uuid, material: &Material) -> Store {
    let store = Store::open(path).unwrap();
    workspace::prepare(path, profile, material).unwrap();
    store
}
fn edit(store: &mut Store, record: Uuid, revision: u64, text: &str) {
    let device = store.device_id().unwrap();
    store
        .edit(
            Uuid::new_v4(),
            record,
            revision,
            device,
            Content::Favorite {
                title: "synthetic client".into(),
                text: text.into(),
            },
            false,
        )
        .unwrap();
}
fn response(profile: Uuid, device: Uuid, operation: &UploadOperation, revision: u64) -> Vec<u8> {
    serde_json::to_vec(&Change {
        account_id: profile,
        device_id: device,
        revision,
        operation: operation.clone(),
    })
    .unwrap()
}
fn page(after: u64, changes: Vec<Change>) -> Vec<u8> {
    let next = after + changes.len() as u64;
    serde_json::to_vec(&zapime_sync::Page {
        changes,
        next_cursor: next,
        head: next,
    })
    .unwrap()
}
fn snapshot(path: &Path) -> Vec<(String, Vec<Vec<String>>)> {
    let db = Connection::open(path).unwrap();
    [
        "metadata",
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
fn descriptor_is_strict_public_identity_of_the_exact_prepared_bytes() {
    use sha2::{Digest, Sha256};
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let descriptor = material.descriptor();
    descriptor.validate().unwrap();
    let expected: String = Sha256::digest(material.encode().as_slice())
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    assert_eq!(descriptor.material_sha256, expected);
    assert_eq!(descriptor.key_id, material.key_id);
    assert_eq!(descriptor.version, 1);
    let json = serde_json::to_value(&descriptor).unwrap();
    assert_eq!(json.as_object().unwrap().len(), 3);
    assert_eq!(
        serde_json::from_value::<Descriptor>(json.clone()).unwrap(),
        descriptor
    );
    for (field, value) in [
        ("version", serde_json::json!(2)),
        ("key_id", serde_json::json!(Uuid::nil())),
        ("material_sha256", serde_json::json!("A".repeat(64))),
        ("material_sha256", serde_json::json!("a".repeat(63))),
        ("material_sha256", serde_json::json!("g".repeat(64))),
    ] {
        let mut invalid = json.clone();
        invalid[field] = value;
        assert!(serde_json::from_value::<Descriptor>(invalid)
            .unwrap()
            .validate()
            .is_err());
    }
    let mut unknown = json.clone();
    unknown["root"] = "never allowed".into();
    assert!(serde_json::from_value::<Descriptor>(unknown).is_err());
    let mut missing = json;
    missing.as_object_mut().unwrap().remove("key_id");
    assert!(serde_json::from_value::<Descriptor>(missing).is_err());
    let wire = serde_json::to_string(&descriptor).unwrap();
    let duplicate = wire.replacen("\"version\":1", "\"version\":1,\"version\":1", 1);
    assert!(serde_json::from_str::<Descriptor>(&duplicate).is_err());
}

#[test]
fn staging_freezes_original_snapshot_and_retries_exact_persisted_bytes_after_restart() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("source.db");
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let mut store = new_store(&path, profile, &material);
    let id = Uuid::new_v4();
    edit(&mut store, id, 0, "original frozen content");
    let first = client::stage_next(&mut store, profile, &material)
        .unwrap()
        .unwrap();
    let wire = client::frozen_wire(&mut store, profile, &material, &first).unwrap();
    let persisted = Connection::open(&path)
        .unwrap()
        .query_row::<String, _, _>(
            "SELECT wire FROM sync_uploads WHERE operation_id=?1",
            [first.operation_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(wire, persisted.as_bytes());
    for revision in 1..=100 {
        edit(
            &mut store,
            id,
            revision,
            &format!("new local edit {revision}"),
        );
    }
    assert_eq!(store.pending_count().unwrap(), 101);
    let retried = client::stage_next(&mut store, profile, &material)
        .unwrap()
        .unwrap();
    assert!(retried == first);
    assert_eq!(store.pending_count().unwrap(), 2);
    assert_eq!(
        client::frozen_wire(&mut store, profile, &material, &retried).unwrap(),
        wire
    );
    let frozen = store
        .staged_upload(profile, first.operation_id)
        .unwrap()
        .unwrap();
    assert_eq!(frozen.snapshot.revision, 1);
    assert_eq!(store.get(id).unwrap().unwrap().revision, 101);
    assert_eq!(
        Connection::open(&path)
            .unwrap()
            .query_row::<i64, _, _>("SELECT count(*) FROM operations", [], |row| row.get(0))
            .unwrap(),
        101
    );
    drop(store);
    let mut reopened = Store::open_backup_writer(&path).unwrap();
    let again = client::stage_next(&mut reopened, profile, &material)
        .unwrap()
        .unwrap();
    assert!(again == first);
    assert_eq!(
        client::frozen_wire(&mut reopened, profile, &material, &again).unwrap(),
        wire
    );
    let device = Uuid::new_v4();
    let ack = response(profile, device, &first, 7);
    client::acknowledge(&mut reopened, profile, device, &material, &first, &ack).unwrap();
    assert_eq!(reopened.sync_cursor(profile).unwrap(), 0);
    assert_eq!(reopened.get(id).unwrap().unwrap().revision, 101);
    assert_eq!(reopened.pending_count().unwrap(), 1);
    let next = client::stage_next(&mut reopened, profile, &material)
        .unwrap()
        .unwrap();
    assert_ne!(next.operation_id, first.operation_id);
    assert_eq!(next.base_revision, 7);
    let before = snapshot(&path);
    client::acknowledge(&mut reopened, profile, device, &material, &first, &ack).unwrap();
    assert_eq!(snapshot(&path), before);
    assert_eq!(
        client::frozen_wire(&mut reopened, profile, &material, &first).unwrap(),
        wire
    );
}

#[test]
fn every_ack_identity_and_complete_operation_field_is_verified_without_mutation() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("source.db");
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let mut store = new_store(&path, profile, &material);
    edit(
        &mut store,
        Uuid::new_v4(),
        0,
        "actual encrypted synthetic record",
    );
    let operation = client::stage_next(&mut store, profile, &material)
        .unwrap()
        .unwrap();
    let device = Uuid::new_v4();
    let ack = response(profile, device, &operation, 1);
    let json: serde_json::Value = serde_json::from_slice(&ack).unwrap();
    let before = snapshot(&path);
    let fields = [
        ("/account_id", serde_json::json!(Uuid::new_v4())),
        ("/device_id", serde_json::json!(Uuid::new_v4())),
        ("/revision", serde_json::json!(0)),
        ("/revision", serde_json::json!(i64::MAX as u64 + 1)),
        ("/operation/operation_id", serde_json::json!(Uuid::new_v4())),
        ("/operation/record_id", serde_json::json!(Uuid::new_v4())),
        ("/operation/key_id", serde_json::json!(Uuid::new_v4())),
        ("/operation/kind", serde_json::json!(5)),
        ("/operation/base_revision", serde_json::json!(1)),
        ("/operation/deleted", serde_json::json!(true)),
        ("/operation/envelope/version", serde_json::json!(2)),
        (
            "/operation/envelope/nonce",
            serde_json::json!("A".repeat(32)),
        ),
        (
            "/operation/envelope/ciphertext",
            serde_json::json!("A".repeat(22)),
        ),
    ];
    for (field, value) in fields {
        let mut wrong = json.clone();
        *wrong.pointer_mut(field).unwrap() = value;
        assert!(
            client::acknowledge(
                &mut store,
                profile,
                device,
                &material,
                &operation,
                &serde_json::to_vec(&wrong).unwrap()
            )
            .is_err(),
            "{field}"
        );
        assert_eq!(snapshot(&path), before, "{field}");
    }
    for container in ["", "/operation", "/operation/envelope"] {
        let mut wrong = json.clone();
        wrong
            .pointer_mut(container)
            .unwrap()
            .as_object_mut()
            .unwrap()
            .insert("unknown".into(), serde_json::json!(0));
        assert!(client::acknowledge(
            &mut store,
            profile,
            device,
            &material,
            &operation,
            &serde_json::to_vec(&wrong).unwrap()
        )
        .is_err());
        assert_eq!(snapshot(&path), before);
    }
    let duplicate = String::from_utf8(ack.clone())
        .unwrap()
        .replacen("\"revision\":1", "\"revision\":1,\"revision\":1", 1)
        .into_bytes();
    let trailing = [ack.as_slice(), b" {}"].concat();
    for wrong in [
        vec![],
        vec![b' '; client::MAX_ACK_BYTES + 1],
        duplicate,
        trailing,
    ] {
        assert!(
            client::acknowledge(&mut store, profile, device, &material, &operation, &wrong)
                .is_err()
        );
        assert_eq!(snapshot(&path), before);
    }
    assert!(client::acknowledge(
        &mut store,
        profile,
        Uuid::nil(),
        &material,
        &operation,
        &ack
    )
    .is_err());
    // A response that agrees with a caller-forged expected operation must still
    // disagree with the actual frozen database row and be rejected.
    let mut caller_forged = operation.clone();
    caller_forged.envelope.nonce = "B".repeat(32);
    let forged_ack = response(profile, device, &caller_forged, 1);
    assert!(matches!(
        client::acknowledge(
            &mut store,
            profile,
            device,
            &material,
            &caller_forged,
            &forged_ack
        ),
        Err(client::Error::StagedMismatch)
    ));
    assert_eq!(snapshot(&path), before);
    let mut never_staged = operation.clone();
    never_staged.operation_id = Uuid::new_v4();
    assert!(matches!(
        client::acknowledge(
            &mut store,
            profile,
            device,
            &material,
            &never_staged,
            &response(profile, device, &never_staged, 1)
        ),
        Err(client::Error::Unstaged)
    ));
    assert_eq!(snapshot(&path), before);
    client::acknowledge(&mut store, profile, device, &material, &operation, &ack).unwrap();
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(store.sync_cursor(profile).unwrap(), 0);
}

#[test]
fn two_prepared_stores_exchange_real_ciphertext_and_do_not_echo_downloads() {
    let dir = tempfile::tempdir().unwrap();
    let profile = Uuid::new_v4();
    let a_material = Material::generate(profile).unwrap();
    let b_material = Material::decode(profile, &a_material.encode()).unwrap();
    let mut a = new_store(&dir.path().join("a.db"), profile, &a_material);
    let mut b = new_store(&dir.path().join("b.db"), profile, &b_material);
    let record = Uuid::new_v4();
    edit(&mut a, record, 0, "两个本机资料库的真实加密交换");
    let first = client::stage_next(&mut a, profile, &a_material)
        .unwrap()
        .unwrap();
    let wire = client::frozen_wire(&mut a, profile, &a_material, &first).unwrap();
    assert!(!String::from_utf8_lossy(&wire).contains("两个本机资料库"));
    let server_a = Uuid::new_v4();
    let first_change: Change =
        serde_json::from_slice(&response(profile, server_a, &first, 1)).unwrap();
    let first_page = page(0, vec![first_change]);
    let received = client::apply_page(&mut b, profile, &b_material, 0, &first_page).unwrap();
    assert_eq!(received.applied, 1);
    assert_eq!(b.get(record).unwrap(), a.get(record).unwrap());
    assert_eq!(b.pending_count().unwrap(), 0);
    assert!(client::stage_next(&mut b, profile, &b_material)
        .unwrap()
        .is_none());
    client::acknowledge(
        &mut a,
        profile,
        server_a,
        &a_material,
        &first,
        &response(profile, server_a, &first, 1),
    )
    .unwrap();
    assert_eq!(a.sync_cursor(profile).unwrap(), 0);
    assert_eq!(
        client::apply_page(&mut a, profile, &a_material, 0, &first_page)
            .unwrap()
            .echoed,
        1
    );
    edit(&mut b, record, 1, "另一台设备的显式修改");
    let second = client::stage_next(&mut b, profile, &b_material)
        .unwrap()
        .unwrap();
    assert_eq!(second.base_revision, 1);
    let server_b = Uuid::new_v4();
    let second_page = page(
        1,
        vec![serde_json::from_slice(&response(profile, server_b, &second, 2)).unwrap()],
    );
    assert_eq!(
        client::apply_page(&mut a, profile, &a_material, 1, &second_page)
            .unwrap()
            .applied,
        1
    );
    assert_eq!(a.get(record).unwrap(), b.get(record).unwrap());
    assert_eq!(a.pending_count().unwrap(), 0);
    client::acknowledge(
        &mut b,
        profile,
        server_b,
        &b_material,
        &second,
        &response(profile, server_b, &second, 2),
    )
    .unwrap();
    assert_eq!(
        client::apply_page(&mut b, profile, &b_material, 1, &second_page)
            .unwrap()
            .echoed,
        1
    );
    assert_eq!(a.sync_cursor(profile).unwrap(), 2);
    assert_eq!(b.sync_cursor(profile).unwrap(), 2);
    assert_eq!(
        client::pull_cursor(&mut a, profile, &a_material).unwrap(),
        2
    );
}

#[test]
fn wrong_material_and_unprepared_stores_never_stage_ack_apply_or_bind() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("source.db");
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let mut store = new_store(&path, profile, &material);
    edit(&mut store, Uuid::new_v4(), 0, "synthetic");
    let operation = client::stage_next(&mut store, profile, &material)
        .unwrap()
        .unwrap();
    let device = Uuid::new_v4();
    let ack = response(profile, device, &operation, 1);
    let empty_page = page(0, vec![]);
    let different_key = Material::generate(profile).unwrap();
    let mut bytes = material.encode();
    bytes[40] ^= 1;
    let changed_root = Material::decode(profile, &bytes).unwrap();
    let wrong_account = Material::generate(Uuid::new_v4()).unwrap();
    for wrong in [&different_key, &changed_root, &wrong_account] {
        let before = snapshot(&path);
        assert!(client::stage_next(&mut store, profile, wrong).is_err());
        assert!(client::pull_cursor(&mut store, profile, wrong).is_err());
        assert!(client::frozen_wire(&mut store, profile, wrong, &operation).is_err());
        assert!(client::acknowledge(&mut store, profile, device, wrong, &operation, &ack).is_err());
        assert!(client::apply_page(&mut store, profile, wrong, 0, &empty_page).is_err());
        assert_eq!(snapshot(&path), before);
    }
    let unbound_path = dir.path().join("unbound.db");
    let mut unbound = Store::open(&unbound_path).unwrap();
    let before = snapshot(&unbound_path);
    assert!(client::stage_next(&mut unbound, profile, &material).is_err());
    assert!(client::pull_cursor(&mut unbound, profile, &material).is_err());
    assert!(client::frozen_wire(&mut unbound, profile, &material, &operation).is_err());
    assert!(
        client::acknowledge(&mut unbound, profile, device, &material, &operation, &ack).is_err()
    );
    assert!(client::apply_page(&mut unbound, profile, &material, 0, &empty_page).is_err());
    assert_eq!(snapshot(&unbound_path), before);
    assert!(unbound.sync_profile().unwrap().is_none());
}

#[test]
fn oversized_serialized_records_remain_intact_and_pending_without_a_stage() {
    let dir = tempfile::tempdir().unwrap();
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    // Both texts are locally valid. The first exceeds the cipher budget only
    // after Record metadata, and the second after JSON escaping.
    for (name, text) in [
        ("metadata", "x".repeat(65536)),
        ("escaping", "\u{1}".repeat(12000)),
    ] {
        let path = dir.path().join(format!("{name}.db"));
        let mut store = new_store(&path, profile, &material);
        edit(&mut store, Uuid::new_v4(), 0, &text);
        let before = snapshot(&path);
        assert!(matches!(
            client::stage_next(&mut store, profile, &material),
            Err(client::Error::RecordTooLarge)
        ));
        assert_eq!(snapshot(&path), before);
        assert_eq!(store.pending_count().unwrap(), 1);
    }
}

#[test]
fn failed_second_cipher_and_missing_key_retain_whole_page_without_advancing_cursor() {
    let dir = tempfile::tempdir().unwrap();
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let mut source = new_store(&dir.path().join("source.db"), profile, &material);
    edit(&mut source, Uuid::new_v4(), 0, "synthetic first");
    let first = client::stage_next(&mut source, profile, &material)
        .unwrap()
        .unwrap();
    let device = Uuid::new_v4();
    client::acknowledge(
        &mut source,
        profile,
        device,
        &material,
        &first,
        &response(profile, device, &first, 1),
    )
    .unwrap();
    edit(&mut source, Uuid::new_v4(), 0, "synthetic second");
    let second = client::stage_next(&mut source, profile, &material)
        .unwrap()
        .unwrap();
    let good = page(
        0,
        vec![
            serde_json::from_slice(&response(profile, device, &first, 1)).unwrap(),
            serde_json::from_slice(&response(profile, device, &second, 2)).unwrap(),
        ],
    );
    let mut corrupted: serde_json::Value = serde_json::from_slice(&good).unwrap();
    let ciphertext = corrupted["changes"][1]["operation"]["envelope"]["ciphertext"]
        .as_str()
        .unwrap();
    corrupted["changes"][1]["operation"]["envelope"]["ciphertext"] = format!(
        "{}{}",
        if ciphertext.starts_with('A') {
            "B"
        } else {
            "A"
        },
        &ciphertext[1..]
    )
    .into();
    let mut missing: serde_json::Value = serde_json::from_slice(&good).unwrap();
    missing["changes"][1]["operation"]["key_id"] = Uuid::new_v4().to_string().into();
    for (name, bad) in [("corrupt", corrupted), ("missing", missing)] {
        let path = dir.path().join(format!("{name}.db"));
        let mut target = new_store(&path, profile, &material);
        let wire = serde_json::to_vec(&bad).unwrap();
        let error = client::apply_page(&mut target, profile, &material, 0, &wire)
            .err()
            .unwrap();
        match error {
            client::Error::Page(error) => assert!(matches!(error.retention, Retention::Saved(_))),
            _ => panic!("expected a preserved authenticated-boundary failure"),
        }
        assert!(target.records(true).unwrap().is_empty());
        assert_eq!(target.pending_count().unwrap(), 0);
        assert_eq!(target.sync_cursor(profile).unwrap(), 0);
        let summaries = target.retained_pulls(profile, 0, 20).unwrap();
        assert_eq!(summaries.len(), 1);
        assert_eq!(
            target
                .retained_pull(profile, &summaries[0].id)
                .unwrap()
                .unwrap()
                .wire
                .as_bytes(),
            wire
        );
        assert_eq!(
            client::apply_page(&mut target, profile, &material, 0, &good)
                .unwrap()
                .applied,
            2
        );
        assert_eq!(target.sync_cursor(profile).unwrap(), 2);
        assert_eq!(target.retained_pulls(profile, 0, 20).unwrap().len(), 1);
    }
}

#[test]
fn altered_persistent_ciphertext_or_snapshot_is_never_accepted_as_a_frozen_request() {
    let dir = tempfile::tempdir().unwrap();
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    for name in ["tampered-cipher", "authenticated-wrong-snapshot"] {
        let path = dir.path().join(format!("{name}.db"));
        let mut store = new_store(&path, profile, &material);
        edit(&mut store, Uuid::new_v4(), 0, "original snapshot");
        let mut operation = client::stage_next(&mut store, profile, &material)
            .unwrap()
            .unwrap();
        if name == "tampered-cipher" {
            let ciphertext = &mut operation.envelope.ciphertext;
            ciphertext.replace_range(
                ..1,
                if ciphertext.starts_with('A') {
                    "B"
                } else {
                    "A"
                },
            );
        } else {
            let frozen = store
                .staged_upload(profile, operation.operation_id)
                .unwrap()
                .unwrap();
            let mut different = frozen.snapshot;
            different.content = Content::Favorite {
                title: "synthetic client".into(),
                text: "different authenticated plaintext".into(),
            };
            let bytes = material.encode();
            let key = SecretKey::from_protected_storage(&bytes[40..]).unwrap();
            let binding = Binding {
                profile_id: profile,
                record_id: operation.record_id,
                operation_id: operation.operation_id,
                key_id: material.key_id,
                kind: operation.kind,
            };
            operation.envelope = serde_json::from_slice(
                &zapime_crypto::seal(&key, &binding, &serde_json::to_vec(&different).unwrap())
                    .unwrap(),
            )
            .unwrap();
        }
        Connection::open(&path)
            .unwrap()
            .execute(
                "UPDATE sync_uploads SET wire=?1 WHERE operation_id=?2",
                rusqlite::params![
                    String::from_utf8(operation.to_wire().unwrap()).unwrap(),
                    operation.operation_id.to_string()
                ],
            )
            .unwrap();
        let before = snapshot(&path);
        assert!(matches!(
            client::frozen_wire(&mut store, profile, &material, &operation),
            Err(client::Error::Ciphertext)
        ));
        let device = Uuid::new_v4();
        assert!(matches!(
            client::acknowledge(
                &mut store,
                profile,
                device,
                &material,
                &operation,
                &response(profile, device, &operation, 1)
            ),
            Err(client::Error::Ciphertext)
        ));
        assert_eq!(snapshot(&path), before);
    }
}

#[test]
fn interrupted_authenticated_page_rolls_back_records_receipts_and_cursor_before_retry() {
    let dir = tempfile::tempdir().unwrap();
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let mut source = new_store(&dir.path().join("source.db"), profile, &material);
    let device = Uuid::new_v4();
    edit(
        &mut source,
        Uuid::new_v4(),
        0,
        "synthetic first budget record",
    );
    let first = client::stage_next(&mut source, profile, &material)
        .unwrap()
        .unwrap();
    client::acknowledge(
        &mut source,
        profile,
        device,
        &material,
        &first,
        &response(profile, device, &first, 1),
    )
    .unwrap();
    edit(
        &mut source,
        Uuid::new_v4(),
        0,
        "synthetic second budget record",
    );
    let second = client::stage_next(&mut source, profile, &material)
        .unwrap()
        .unwrap();
    let wire = page(
        0,
        vec![
            serde_json::from_slice(&response(profile, device, &first, 1)).unwrap(),
            serde_json::from_slice(&response(profile, device, &second, 2)).unwrap(),
        ],
    );
    let path = dir.path().join("target.db");
    let mut target = new_store(&path, profile, &material);
    let raw = Connection::open(&path).unwrap();
    raw.execute_batch("CREATE TRIGGER slow_pull BEFORE INSERT ON sync_received WHEN NEW.server_revision=2 BEGIN SELECT sum(x) FROM (WITH RECURSIVE work(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM work WHERE x<100000000) SELECT x FROM work); END").unwrap();
    let before = snapshot(&path);
    let error = client::apply_page(&mut target, profile, &material, 0, &wire)
        .err()
        .unwrap();
    match error {
        client::Error::Page(error) => {
            assert!(matches!(error.retention, Retention::NotEligible));
            match *error.cause {
                zapime_sync::Error::Data(zapime_data::Error::Storage(
                    rusqlite::Error::SqliteFailure(sqlite, _),
                )) => assert_eq!(sqlite.code, rusqlite::ErrorCode::OperationInterrupted),
                _ => panic!("expected an actual SQLite VM interruption"),
            }
        }
        _ => panic!("expected preserving boundary to report the actual transaction failure"),
    }
    assert_eq!(snapshot(&path), before);
    assert_eq!(target.sync_cursor(profile).unwrap(), 0);
    raw.execute_batch("DROP TRIGGER slow_pull").unwrap();
    assert_eq!(
        client::apply_page(&mut target, profile, &material, 0, &wire)
            .unwrap()
            .applied,
        2
    );
    assert_eq!(target.sync_cursor(profile).unwrap(), 2);
}
