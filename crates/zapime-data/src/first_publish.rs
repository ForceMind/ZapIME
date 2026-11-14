// SPDX-License-Identifier: GPL-3.0-only
//! Explicit first-publication preparation for a records-only recovery.
//! This is not a background sync or permission to claim remote absence.
use crate::{
    sync::{server_revision, workspace_binding, workspace_budget, WorkspaceBinding},
    Error, Record, Store,
};
use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use uuid::Uuid;

/// Bound one user-confirmed transaction; the existing manual sync worker
/// independently limits its staged network operations.
pub const MAX_FIRST_PUBLISH_SELECTION: usize = 16;
const MAX_ENCRYPTABLE_RECORD_BYTES: usize = 65_536;

/// Pin the exact existing record bytes, not only its mutable local revision.
/// Non-secret metadata intended for a trusted host, never a transferable
/// approval token or evidence of an already completed cloud backup.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FirstPublishSelection {
    pub record_id: Uuid,
    pub operation_id: Uuid,
    pub local_revision: u64,
    pub body_sha256: [u8; 32],
}

/// Only called inside a transaction with the full workspace binding checked.
/// This validates the whole selected record without returning its body to UI.
fn snapshot(tx: &Transaction<'_>, id: Uuid) -> Result<(String, FirstPublishSelection), Error> {
    if id.is_nil() {
        return Err(Error::Invalid);
    }
    let pair: Option<(Option<String>, i64)> = tx
        .query_row(
            "SELECT CASE WHEN length(CAST(body AS BLOB))<=?2 THEN body END,revision
             FROM records WHERE id=?1",
            params![id.to_string(), MAX_ENCRYPTABLE_RECORD_BYTES as i64],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let (body, db_revision) = pair.ok_or(Error::Invalid)?;
    let body = body.ok_or(Error::Invalid)?;
    let record: Record = serde_json::from_str(&body)?;
    if record.id != id
        || record.device_id.is_nil()
        || record.deleted
        || db_revision <= 0
        || record.revision != db_revision as u64
        || record.revision > i64::MAX as u64
        || serde_json::to_vec(&record)?.len() > MAX_ENCRYPTABLE_RECORD_BYTES
    {
        // Old tombstones are deliberately excluded; uploading an unknown old
        // deletion is not an acceptable way to resolve remote history.
        return Err(Error::SyncLocalConflict);
    }
    record.content.validate()?;
    Ok((
        body.clone(),
        FirstPublishSelection {
            record_id: id,
            operation_id: Uuid::nil(), // assigned once when the host starts approval
            local_revision: record.revision,
            body_sha256: Sha256::digest(body.as_bytes()).into(),
        },
    ))
}

fn matches_current(tx: &Transaction<'_>, chosen: &FirstPublishSelection) -> Result<String, Error> {
    let (body, actual) = snapshot(tx, chosen.record_id)?;
    if actual.local_revision != chosen.local_revision
        || actual.body_sha256 != chosen.body_sha256
    {
        return Err(Error::Conflict);
    }
    Ok(body)
}

fn has_other_history(tx: &Transaction<'_>, id: Uuid, own_op: Uuid) -> Result<bool, Error> {
    // Use the existing expression index and inspect completed operations too.
    // A regular downloaded record must not become a new local outgoing edit.
    Ok(tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM operations INDEXED BY sync_pending_record
           WHERE pending IN(0,1) AND json_extract(result,'$.id')=?1 AND id<>?2)",
        params![id.to_string(), own_op.to_string()],
        |row| row.get(0),
    )?)
}

fn check_workspace(tx: &Transaction<'_>, binding: &WorkspaceBinding, cursor: u64) -> Result<(), Error> {
    if binding.profile_id.is_nil() || binding.key_id.is_nil() || cursor > i64::MAX as u64 {
        return Err(Error::Invalid);
    }
    if workspace_binding(tx)?.as_ref() != Some(binding) {
        return Err(Error::SyncWorkspace);
    }
    let stored: String = tx.query_row(
        "SELECT value FROM metadata WHERE key='sync_cursor'",
        [],
        |row| row.get(0),
    )?;
    if stored != cursor.to_string() {
        return Err(Error::SyncCursor);
    }
    Ok(())
}

impl Store {
    /// Read-only selection inspection after the host has completed a trusted
    /// authenticated pull to its current head. Nothing is staged or queued.
    /// The caller supplies a fresh operation UUID only for later explicit
    /// user approval. The byte digest must never be sent as a network token.
    pub fn inspect_first_publish(
        &mut self,
        binding: &WorkspaceBinding,
        cursor: u64,
        id: Uuid,
        operation_id: Uuid,
    ) -> Result<FirstPublishSelection, Error> {
        if operation_id.is_nil() {
            return Err(Error::Invalid);
        }
        workspace_budget(&mut self.db, |db| {
            let tx = db.transaction()?;
            check_workspace(&tx, binding, cursor)?;
            let (_, mut selected) = snapshot(&tx, id)?;
            if server_revision(&tx, id)? != 0 || has_other_history(&tx, id, operation_id)? {
                return Err(Error::SyncLocalConflict);
            }
            // Even an old local operation with this same UUID is not proof of
            // user consent. Only a never-seen operation can be newly selected.
            let reused: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM operations WHERE id=?1)",
                [operation_id.to_string()],
                |row| row.get(0),
            )?;
            if reused {
                return Err(Error::OperationReused);
            }
            selected.operation_id = operation_id;
            Ok(selected)
        })
    }

    /// An explicit manual action, never triggered by restore/pull/inventory.
    /// The calling sync layer must hold a terminal pull proof from an
    /// authenticated session and user consent. All checks and queue inserts
    /// commit atomically, preserving IDs, revisions, bodies and tombstones.
    pub fn enqueue_first_publish(
        &mut self,
        binding: &WorkspaceBinding,
        cursor: u64,
        selections: &[FirstPublishSelection],
        confirmed: bool,
    ) -> Result<usize, Error> {
        if !confirmed || selections.is_empty() || selections.len() > MAX_FIRST_PUBLISH_SELECTION {
            return Err(Error::Invalid);
        }
        let mut ids = HashSet::new();
        let mut operations = HashSet::new();
        for selection in selections {
            if selection.record_id.is_nil()
                || selection.operation_id.is_nil()
                || selection.local_revision == 0
                || selection.local_revision > i64::MAX as u64
                || !ids.insert(selection.record_id)
                || !operations.insert(selection.operation_id)
            {
                return Err(Error::Invalid);
            }
        }
        workspace_budget(&mut self.db, |db| {
            let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
            check_workspace(&tx, binding, cursor)?;
            let mut inserted = 0usize;
            for chosen in selections {
                let body = matches_current(&tx, chosen)?;
                let request = serde_json::to_string(&serde_json::json!({
                    "kind": "explicit_first_publish",
                    "version": 1,
                    "record_id": chosen.record_id,
                    "local_revision": chosen.local_revision,
                    "confirmed_cursor": cursor,
                    "body_sha256": hex::encode(chosen.body_sha256),
                }))?;
                let previous: Option<(String, String, i64)> = tx
                    .query_row(
                        "SELECT request,result,pending FROM operations WHERE id=?1",
                        [chosen.operation_id.to_string()],
                        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                    )
                    .optional()?;
                // Retrying an already queued/acknowledged *identical* operation
                // is a no-op. Another historical operation always blocks it.
                if has_other_history(&tx, chosen.record_id, chosen.operation_id)? {
                    return Err(Error::SyncLocalConflict);
                }
                if let Some((old_request, old_body, pending)) = previous {
                    if old_request != request || old_body != body || !(0..=1).contains(&pending) {
                        return Err(Error::OperationReused);
                    }
                    continue;
                }
                if server_revision(&tx, chosen.record_id)? != 0 {
                    return Err(Error::SyncLocalConflict);
                }
                tx.execute(
                    "INSERT INTO operations(id,request,result,pending) VALUES(?1,?2,?3,1)",
                    params![chosen.operation_id.to_string(), request, body],
                )?;
                inserted += 1;
            }
            tx.commit()?;
            Ok(inserted)
        })
    }
}
