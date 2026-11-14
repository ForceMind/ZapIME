// SPDX-License-Identifier: GPL-3.0-only
//! Isolated client/crypto/SQLite proof: this does not impersonate actual HTTP.
use std::path::Path;
use uuid::Uuid;
use zapime_data::{Content, Store};
use zapime_sync::{
    client,
    workspace::{self, Material},
    Change, Page,
};

fn record(store: &mut Store, id: Uuid, text: &str, deleted: bool) {
    store
        .edit(
            Uuid::new_v4(),
            id,
            0,
            Uuid::new_v4(),
            Content::Favorite {
                title: "Synthetic".into(),
                text: text.into(),
            },
            deleted,
        )
        .unwrap();
}
fn recovered(path: &Path, profile: Uuid, key: &Material, id: Uuid) -> Store {
    let mut origin = Store::open(&path.with_extension("origin")).unwrap();
    record(&mut origin, id, "Synthetic records-only import", false);
    let archive = origin.export_plaintext().unwrap();
    let mut local = Store::open(path).unwrap();
    assert_eq!(local.restore_unbound_plaintext(&archive).unwrap(), 1);
    workspace::prepare(path, profile, key).unwrap();
    assert_eq!(local.pending_count().unwrap(), 0);
    local
}
fn empty_response(cursor: u64) -> Vec<u8> {
    serde_json::to_vec(&Page {
        changes: vec![],
        next_cursor: cursor,
        head: cursor,
    })
    .unwrap()
}

#[test]
fn terminal_pull_user_consent_enqueues_exact_restored_snapshot_and_retries_frozen_wire() {
    let dir = tempfile::tempdir().unwrap();
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let id = Uuid::new_v4();
    let path = dir.path().join("recovered.db");
    let mut store = recovered(&path, profile, &material, id);
    let before = store.export_plaintext().unwrap();

    let (status, proof) = client::apply_page_for_first_publish(
        &mut store, profile, &material, 0, &empty_response(0)
    )
    .unwrap();
    assert_eq!(status.cursor, 0);
    let proof = proof.expect("terminal pull should supply ephemeral head evidence");
    let selected = client::inspect_first_publish(&mut store, profile, &material, &proof, id).unwrap();
    assert_eq!(selected.local_revision, 1);
    assert!(matches!(
        client::confirm_first_publish(
            &mut store, profile, &material, &proof, &[selected.clone()], false
        ),
        Err(client::Error::FirstPublishConsent)
    ));
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(
        client::confirm_first_publish(
            &mut store, profile, &material, &proof, &[selected.clone()], true
        )
        .unwrap(),
        1
    );
    assert_eq!(before, store.export_plaintext().unwrap());
    let staged = client::stage_next(&mut store, profile, &material).unwrap().unwrap();
    assert_eq!(staged.operation_id, selected.operation_id);
    assert_eq!(staged.record_id, id);
    assert_eq!(staged.base_revision, 0);
    let bytes = client::frozen_wire(&mut store, profile, &material, &staged).unwrap();
    assert_eq!(bytes, client::frozen_wire(&mut store, profile, &material, &staged).unwrap());

    let device = Uuid::new_v4();
    let ack = serde_json::to_vec(&Change {
        account_id: profile,
        device_id: device,
        revision: 1,
        operation: staged.clone(),
    }).unwrap();
    client::acknowledge(&mut store, profile, device, &material, &staged, &ack).unwrap();
    assert_eq!(store.pending_count().unwrap(), 0);
    assert_eq!(
        client::confirm_first_publish(&mut store, profile, &material, &proof, &[selected], true)
            .unwrap(),
        0
    );
    assert_eq!(before, store.export_plaintext().unwrap());
}

#[test]
fn nonterminal_or_conflicting_pull_does_not_create_upload_authority() {
    let dir = tempfile::tempdir().unwrap();
    let profile = Uuid::new_v4();
    let material = Material::generate(profile).unwrap();
    let id = Uuid::new_v4();
    let mut local = recovered(&dir.path().join("conflict.db"), profile, &material, id);

    // A response claiming an unfulfilled head must never create consent proof.
    let ahead = serde_json::to_vec(&Page {
        changes: vec![],
        next_cursor: 0,
        head: 1,
    }).unwrap();
    assert!(client::apply_page_for_first_publish(&mut local, profile, &material, 0, &ahead).is_err());
    assert_eq!(local.pending_count().unwrap(), 0);

    // A real, bound remote history with overlapping ID but different content
    // is rejected by the existing pull conflict gate, not overwritten.
    let mut publisher = Store::open(&dir.path().join("publisher.db")).unwrap();
    workspace::prepare(&dir.path().join("publisher.db"), profile, &material).unwrap();
    record(&mut publisher, id, "Different remote body", false);
    let upload = client::stage_next(&mut publisher, profile, &material).unwrap().unwrap();
    let page = serde_json::to_vec(&Page {
        changes: vec![Change {
            account_id: profile,
            device_id: Uuid::new_v4(),
            revision: 1,
            operation: upload,
        }],
        next_cursor: 1,
        head: 1,
    }).unwrap();
    assert!(client::apply_page_for_first_publish(&mut local, profile, &material, 0, &page).is_err());
    assert_eq!(client::pull_cursor(&mut local, profile, &material).unwrap(), 0);
    assert_eq!(local.pending_count().unwrap(), 0);
}
