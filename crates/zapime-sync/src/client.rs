// SPDX-License-Identifier: GPL-3.0-only
//! Stateless primitives for an explicitly invoked sync round. No HTTP, input
//! session, timer, account binding or key generation. Never hold a Store
//! transaction across a network request. The host must compare the registered
//! account descriptor before invoking these data-transfer operations.
use crate::{workspace, Change, PreservedError};
use uuid::Uuid;
use zapime_crypto::Binding;
use zapime_data::{
    pull::ApplyReport,
    sync::{StagedUpload, UploadOperation},
    Record, Store,
};
use zeroize::Zeroizing;

pub const MAX_ACK_BYTES: usize = 100_000;
pub const MAX_RECORD_BYTES: usize = 65_536;
const COALESCE_LIMIT: usize = 256;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("sync_client_workspace")]
    Workspace(#[source] workspace::Error),
    #[error("sync_client_storage")]
    Storage(#[source] zapime_data::Error),
    #[error("sync_client_record_too_large")]
    RecordTooLarge,
    #[error("sync_client_ciphertext_invalid")]
    Ciphertext,
    #[error("sync_client_ack_invalid")]
    Acknowledgement,
    #[error("sync_client_operation_not_staged")]
    Unstaged,
    #[error("sync_client_staged_mismatch")]
    StagedMismatch,
    #[error("sync_client_page_rejected")]
    Page(#[source] PreservedError),
}

fn guard(store: &mut Store, profile: Uuid, material: &workspace::Material) -> Result<(), Error> {
    material.validate(profile).map_err(Error::Workspace)?;
    let binding = store.sync_workspace_binding().map_err(Error::Storage)?;
    if binding != Some(material.binding()) {
        return Err(Error::Workspace(workspace::Error::Binding));
    }
    Ok(())
}
fn binding(profile: Uuid, operation: &UploadOperation) -> Binding {
    Binding {
        profile_id: profile,
        record_id: operation.record_id,
        operation_id: operation.operation_id,
        key_id: operation.key_id,
        kind: operation.kind,
    }
}

/// Read-only, bounded local evidence. Untracked rows are not upload permission;
/// the caller must not infer remote absence or completeness from this preview.
pub fn inventory_page(
    store: &mut Store,
    profile: Uuid,
    material: &workspace::Material,
    after: Option<Uuid>,
    limit: usize,
) -> Result<zapime_data::inventory::InventoryPage, Error> {
    material.validate(profile).map_err(Error::Workspace)?;
    store
        .sync_inventory_page(&material.binding(), after, limit)
        .map_err(Error::Storage)
}
fn checked_frozen(
    store: &mut Store,
    profile: Uuid,
    material: &workspace::Material,
    expected: &UploadOperation,
) -> Result<StagedUpload, Error> {
    expected.to_wire().map_err(Error::Storage)?;
    if expected.key_id != material.key_id {
        return Err(Error::StagedMismatch);
    }
    let frozen = store
        .staged_upload(profile, expected.operation_id)
        .map_err(Error::Storage)?
        .ok_or(Error::Unstaged)?;
    if frozen.operation != *expected {
        return Err(Error::StagedMismatch);
    }
    let envelope = serde_json::to_vec(&frozen.operation.envelope).map_err(|_| Error::Ciphertext)?;
    let plaintext = zapime_crypto::open(
        &material.key,
        &binding(profile, &frozen.operation),
        &envelope,
    )
    .map_err(|_| Error::Ciphertext)?;
    let record: Record = serde_json::from_slice(&plaintext).map_err(|_| Error::Ciphertext)?;
    if record != frozen.snapshot {
        return Err(Error::Ciphertext);
    }
    Ok(frozen)
}

/// Coalesce only the bounded unstaged window, then encrypt the oldest original
/// snapshot. stage_upload returns its already frozen request on every retry.
/// Any oversized record remains local and pending; it is never truncated.
pub fn stage_next(
    store: &mut Store,
    profile: Uuid,
    material: &workspace::Material,
) -> Result<Option<UploadOperation>, Error> {
    guard(store, profile, material)?;
    store
        .coalesce_unstaged(profile, COALESCE_LIMIT)
        .map_err(Error::Storage)?;
    let Some(pending) = store
        .pending_operations(profile, 1)
        .map_err(Error::Storage)?
        .pop()
    else {
        return Ok(None);
    };
    // Reuse a frozen request before invoking the random source. A lost network
    // response must remain retryable even if new encryption cannot proceed.
    if let Some(frozen) = store
        .staged_upload(profile, pending.operation_id)
        .map_err(Error::Storage)?
    {
        checked_frozen(store, profile, material, &frozen.operation)?;
        return Ok(Some(frozen.operation));
    }
    let plaintext =
        Zeroizing::new(serde_json::to_vec(&pending.record).map_err(|_| Error::Ciphertext)?);
    if plaintext.len() > MAX_RECORD_BYTES {
        return Err(Error::RecordTooLarge);
    }
    let mut upload = UploadOperation {
        operation_id: pending.operation_id,
        record_id: pending.record.id,
        key_id: material.key_id,
        kind: pending.record.content.kind(),
        base_revision: pending.server_revision,
        deleted: pending.record.deleted,
        envelope: serde_json::from_slice(
            &zapime_crypto::seal(
                &material.key,
                &Binding {
                    profile_id: profile,
                    record_id: pending.record.id,
                    operation_id: pending.operation_id,
                    key_id: material.key_id,
                    kind: pending.record.content.kind(),
                },
                &plaintext,
            )
            .map_err(|_| Error::Ciphertext)?,
        )
        .map_err(|_| Error::Ciphertext)?,
    };
    upload = store
        .stage_upload(profile, upload)
        .map_err(Error::Storage)?;
    // Authenticate the persisted encrypted body and compare its original
    // snapshot, not the fresh nonce discarded by an idempotent retry. Existing
    // AEAD does not independently authenticate server authorship/base_revision.
    checked_frozen(store, profile, material, &upload)?;
    Ok(Some(upload))
}

/// Exact bytes from the committed frozen row. No transaction remains open
/// when this function returns to a transport adapter.
pub fn frozen_wire(
    store: &mut Store,
    profile: Uuid,
    material: &workspace::Material,
    expected: &UploadOperation,
) -> Result<Vec<u8>, Error> {
    guard(store, profile, material)?;
    Ok(checked_frozen(store, profile, material, expected)?.wire)
}

/// Read only the locally committed cursor after checking the full current
/// workspace identity. Neither remote head nor an upload ACK can move it.
pub fn pull_cursor(
    store: &mut Store,
    profile: Uuid,
    material: &workspace::Material,
) -> Result<u64, Error> {
    guard(store, profile, material)?;
    store.sync_cursor(profile).map_err(Error::Storage)
}

/// Server identity, device and complete operation must match the exact locally
/// frozen request. Acknowledgement never creates a stage or advances pull cursor.
pub fn acknowledge(
    store: &mut Store,
    profile: Uuid,
    expected_device: Uuid,
    material: &workspace::Material,
    expected: &UploadOperation,
    response: &[u8],
) -> Result<(), Error> {
    guard(store, profile, material)?;
    if expected_device.is_nil() || response.is_empty() || response.len() > MAX_ACK_BYTES {
        return Err(Error::Acknowledgement);
    }
    let change: Change = serde_json::from_slice(response).map_err(|_| Error::Acknowledgement)?;
    if change.account_id != profile
        || change.device_id != expected_device
        || change.revision == 0
        || change.revision > i64::MAX as u64
        || change.revision <= expected.base_revision
        || change.operation != *expected
    {
        return Err(Error::Acknowledgement);
    }
    checked_frozen(store, profile, material, expected)?;
    store
        .acknowledge_upload(profile, expected.operation_id, change.revision)
        .map_err(Error::Storage)
}

/// The existing preserving boundary authenticates the whole page, atomically
/// applies it and retains eligible failed ciphertext separately. The single
/// prepared key never pretends to resolve missing historical key versions.
pub fn apply_page(
    store: &mut Store,
    profile: Uuid,
    material: &workspace::Material,
    after: u64,
    bytes: &[u8],
) -> Result<ApplyReport, Error> {
    guard(store, profile, material)?;
    crate::apply_encrypted_page_preserving_failure(store, profile, after, bytes, |key_id| {
        (key_id == material.key_id).then_some(&material.key)
    })
    .map_err(Error::Page)
}
