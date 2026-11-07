// SPDX-License-Identifier: GPL-3.0-only
//! Synthetic two-store client/real-server acceptance only. All key material and
//! local plaintext stay in a private temporary directory; no network is used.
use rusqlite::{types::Value as SqlValue, Connection};
use serde::Deserialize;
use serde_json::json;
use std::{fs, io::Write, path::Path};
use uuid::Uuid;
use zapime_data::{sync::UploadOperation, Content, Store};
use zapime_sync::{
    client,
    workspace::{self, Descriptor, Material},
    Retention,
};
use zeroize::Zeroizing;

const FIRST: &str = "synthetic manual client source";
const SECOND: &str = "synthetic manual client edited on B";

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Registered {
    protocol: u8,
    account_id: Uuid,
    state: String,
    descriptor: Descriptor,
    registered_by: Uuid,
    registered_at: String,
}
fn read(path: &Path, limit: usize) -> Vec<u8> {
    let bytes = fs::read(path).expect("fixture input");
    assert!(
        !bytes.is_empty() && bytes.len() <= limit,
        "bounded fixture input"
    );
    bytes
}
fn private(path: &Path, bytes: &[u8]) {
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options
        .open(path)
        .expect("new fixture output")
        .write_all(bytes)
        .expect("fixture output");
}
fn material(dir: &Path) -> Material {
    let bytes = Zeroizing::new(read(&dir.join("material.bin"), workspace::MATERIAL_BYTES));
    let account: Uuid =
        serde_json::from_slice(&read(&dir.join("account.json"), 128)).expect("fixture account");
    Material::decode(account, &bytes).expect("fixture material")
}
fn guarded(material: &Material, path: &Path) -> bool {
    let state: Registered = serde_json::from_slice(&read(path, 4096)).expect("registered response");
    state.protocol == 1
        && state.account_id == material.profile
        && state.state == "registered"
        && !state.registered_by.is_nil()
        && !state.registered_at.is_empty()
        && state.descriptor.validate().is_ok()
        && state.descriptor == material.descriptor()
}
fn database(dir: &Path, device: &str) -> std::path::PathBuf {
    assert!(matches!(device, "a" | "b"), "fixture device");
    dir.join(format!("{device}.sqlite3"))
}
fn open(dir: &Path, device: &str) -> Store {
    Store::open_sync_writer(&database(dir, device)).expect("fixture sync database")
}
fn edit(store: &mut Store, id: Uuid, text: &str) {
    let revision = store
        .get(id)
        .expect("fixture record")
        .map_or(0, |record| record.revision);
    let device = store.device_id().expect("fixture device");
    store
        .edit(
            Uuid::new_v4(),
            id,
            revision,
            device,
            Content::Favorite {
                title: "synthetic manual client".into(),
                text: text.into(),
            },
            false,
        )
        .expect("fixture edit");
}
fn snapshot(path: &Path) -> Vec<Vec<Vec<SqlValue>>> {
    let db = Connection::open(path).expect("fixture snapshot");
    [
        "metadata",
        "records",
        "operations",
        "sync_queue",
        "sync_uploads",
        "sync_versions",
        "sync_received",
    ]
    .into_iter()
    .map(|table| {
        let mut stmt = db
            .prepare(&format!("SELECT * FROM {table} ORDER BY 1"))
            .expect("fixture snapshot table");
        let columns = stmt.column_count();
        stmt.query_map([], |row| {
            (0..columns)
                .map(|column| row.get(column))
                .collect::<Result<Vec<SqlValue>, _>>()
        })
        .expect("fixture snapshot rows")
        .collect::<Result<Vec<_>, _>>()
        .expect("fixture snapshot values")
    })
    .collect()
}
fn status(store: &mut Store, material: &Material, command: &str) {
    println!(
        "{}",
        json!({"status":"PASS", "command":command,
        "records":store.records(true).expect("fixture count").len(),
        "pending":store.pending_count().expect("fixture count"),
        "cursor":client::pull_cursor(store, material.profile, material).expect("fixture cursor")})
    );
}
fn main() {
    // Assertion failures must not format plaintext records or secret inputs in
    // uploaded CI logs. The assertions below deliberately carry fixed labels.
    std::panic::set_hook(Box::new(|_| {
        eprintln!("FAIL: manual client fixture assertion")
    }));
    let args: Vec<_> = std::env::args().collect();
    let command = args.get(1).map(String::as_str).expect("fixture command");
    let dir = Path::new(args.get(2).expect("fixture directory"));
    assert!(
        dir.is_absolute() && dir.is_dir(),
        "private fixture directory"
    );
    if command == "init" {
        assert!(args.len() == 4, "init arguments");
        let profile = Uuid::parse_str(&args[3]).expect("fixture account");
        let material = Material::generate(profile).expect("fixture material");
        private(
            &dir.join("account.json"),
            &serde_json::to_vec(&profile).unwrap(),
        );
        private(&dir.join("material.bin"), &material.encode());
        private(
            &dir.join("descriptor.json"),
            &serde_json::to_vec(&material.descriptor()).unwrap(),
        );
        for device in ["a", "b"] {
            let path = database(dir, device);
            drop(Store::open(&path).expect("new fixture store"));
            workspace::prepare(&path, profile, &material).expect("prepare real workspace");
        }
        let mut a = open(dir, "a");
        let id = Uuid::new_v4();
        private(&dir.join("record.json"), &serde_json::to_vec(&id).unwrap());
        edit(&mut a, id, FIRST);
        status(&mut a, &material, command);
        return;
    }
    let material = material(dir);
    if command == "edit-b" {
        assert!(args.len() == 3, "edit arguments");
        let mut b = open(dir, "b");
        let id: Uuid = serde_json::from_slice(&read(&dir.join("record.json"), 128)).unwrap();
        assert!(b.get(id).unwrap().is_some(), "download before edit");
        edit(&mut b, id, SECOND);
        status(&mut b, &material, command);
        return;
    }
    if command == "verify" {
        assert!(args.len() == 3, "verify arguments");
        let mut a = open(dir, "a");
        let mut b = open(dir, "b");
        let records = a.records(true).unwrap();
        assert!(
            records == b.records(true).unwrap() && records.len() == 1,
            "stores converge"
        );
        assert!(
            matches!(&records[0].content, Content::Favorite {text, ..} if text == SECOND),
            "remote edit converges"
        );
        for store in [&mut a, &mut b] {
            assert!(store.pending_count().unwrap() == 0, "no echo upload");
            assert!(
                client::stage_next(store, material.profile, &material)
                    .unwrap()
                    .is_none(),
                "no new ciphertext from download"
            );
            assert!(
                client::pull_cursor(store, material.profile, &material).unwrap() == 2,
                "committed cursor"
            );
        }
        println!(
            "{}",
            json!({"status":"PASS","command":command,"stores":2,"records_each":1,"pending_each":0,"cursor_each":2})
        );
        return;
    }
    let device = args.get(3).expect("fixture device");
    let registered = Path::new(args.get(4).expect("registered response"));
    if command == "reject-descriptor" {
        assert!(args.len() == 5, "descriptor arguments");
        let path = database(dir, device);
        let before = snapshot(&path);
        assert!(!guarded(&material, registered), "wrong descriptor refused");
        assert!(
            before == snapshot(&path),
            "descriptor check cannot mutate database"
        );
        println!(
            "{}",
            json!({"status":"PASS","command":command,"mutation":false})
        );
        return;
    }
    assert!(
        guarded(&material, registered),
        "registered key identity required before transfer"
    );
    let mut store = open(dir, device);
    match command {
        "stage" => {
            assert!(args.len() == 6, "stage arguments");
            let operation = client::stage_next(&mut store, material.profile, &material)
                .unwrap()
                .expect("pending fixture operation");
            let wire =
                client::frozen_wire(&mut store, material.profile, &material, &operation).unwrap();
            assert!(
                !String::from_utf8_lossy(&wire).contains(FIRST)
                    && !String::from_utf8_lossy(&wire).contains(SECOND),
                "ciphertext only"
            );
            private(Path::new(&args[5]), &wire);
        }
        "ack" => {
            assert!(args.len() == 8, "ack arguments");
            let expected_device = Uuid::parse_str(&args[5]).expect("server device");
            let wire = read(Path::new(&args[6]), client::MAX_ACK_BYTES);
            let operation: UploadOperation = serde_json::from_slice(&wire).expect("frozen request");
            assert!(
                client::frozen_wire(&mut store, material.profile, &material, &operation).unwrap()
                    == wire,
                "persisted original wire"
            );
            client::acknowledge(
                &mut store,
                material.profile,
                expected_device,
                &material,
                &operation,
                &read(Path::new(&args[7]), client::MAX_ACK_BYTES),
            )
            .expect("real server acknowledgement");
        }
        "apply" | "reject-page" => {
            assert!(args.len() == 6, "page arguments");
            let wire = read(Path::new(&args[5]), zapime_data::quarantine::MAX_PAGE_BYTES);
            let after = client::pull_cursor(&mut store, material.profile, &material).unwrap();
            if command == "reject-page" {
                let before = snapshot(&database(dir, device));
                let retained = store.retained_pulls(material.profile, 0, 20).unwrap().len();
                let error =
                    client::apply_page(&mut store, material.profile, &material, after, &wire)
                        .expect_err("reject authenticated page");
                assert!(
                    matches!(error, client::Error::Page(error) if matches!(error.retention, Retention::Saved(_))),
                    "failed ciphertext preserved"
                );
                assert!(
                    snapshot(&database(dir, device)) == before,
                    "whole page business rollback"
                );
                let summaries = store.retained_pulls(material.profile, 0, 20).unwrap();
                assert!(summaries.len() == retained + 1, "retention added");
                let retained_wire = store
                    .retained_pull(material.profile, &summaries.last().unwrap().id)
                    .unwrap()
                    .unwrap();
                assert!(
                    retained_wire.wire.as_bytes() == wire,
                    "exact failed ciphertext retained"
                );
            } else {
                let report =
                    client::apply_page(&mut store, material.profile, &material, after, &wire)
                        .expect("real server authenticated page");
                println!(
                    "{}",
                    json!({"status":"PASS","command":"page_counts","applied":report.applied,"echoed":report.echoed})
                );
            }
        }
        _ => panic!("fixture command"),
    }
    status(&mut store, &material, command);
}
