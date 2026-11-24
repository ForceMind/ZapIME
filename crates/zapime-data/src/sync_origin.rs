// SPDX-License-Identifier: GPL-3.0-only
//! Sources of locally edited snapshots eligible for automatic sync.
use crate::{sync::WorkspaceBinding, Error, Record};
use rusqlite::{params, Transaction};
use uuid::Uuid;

pub(crate) fn prepare(tx: &Transaction<'_>) -> Result<(), Error> {
    tx.execute_batch(
        "CREATE TABLE IF NOT EXISTS sync_local_origins(
             operation_id TEXT PRIMARY KEY REFERENCES operations(id),
             record_id TEXT NOT NULL,
             local_revision INTEGER NOT NULL CHECK(local_revision>0),
             profile_id TEXT NOT NULL,
             key_id TEXT NOT NULL,
             material_sha256 TEXT NOT NULL);
         CREATE INDEX IF NOT EXISTS sync_local_origins_record
             ON sync_local_origins(record_id,local_revision);
         CREATE TABLE IF NOT EXISTS sync_pulled_origins(
             record_id TEXT PRIMARY KEY REFERENCES records(id),
             local_revision INTEGER NOT NULL CHECK(local_revision>0),
             profile_id TEXT NOT NULL,
             key_id TEXT NOT NULL,
             material_sha256 TEXT NOT NULL);",
    )?;
    Ok(())
}

/// Workspace metadata is read from the same write transaction as the record.
/// No workspace scan is performed on the ordinary local edit path. The full
/// canonical binding and pending/frozen history are checked on the sync worker.
pub(crate) fn mark_local_mutation(
    tx: &Transaction<'_>,
    operation: Uuid,
    record: &Record,
) -> Result<(), Error> {
    tx.execute(
        "INSERT INTO sync_local_origins
             (operation_id,record_id,local_revision,profile_id,key_id,material_sha256)
         SELECT ?1,?2,?3,p.value,k.value,f.value
         FROM metadata p, metadata k, metadata f
         WHERE p.key='sync_profile' AND k.key='sync_key_id' AND f.key='sync_material_sha256'
           AND length(p.value)=36 AND length(k.value)=36 AND length(f.value)=64
           AND ((?3=1 AND NOT ?4) OR EXISTS(
             SELECT 1 FROM sync_local_origins r
             WHERE r.record_id=?2 AND r.local_revision=?3-1
               AND r.profile_id=p.value AND r.key_id=k.value AND r.material_sha256=f.value)
             OR EXISTS(
             SELECT 1 FROM sync_pulled_origins r
             WHERE r.record_id=?2 AND r.local_revision=?3-1
               AND r.profile_id=p.value AND r.key_id=k.value AND r.material_sha256=f.value))",
        params![
            operation.to_string(),
            record.id.to_string(),
            record.revision as i64,
            record.deleted
        ],
    )?;
    Ok(())
}

pub(crate) fn mark_pulled_record(
    tx: &Transaction<'_>,
    binding: &WorkspaceBinding,
    record: &Record,
) -> Result<(), Error> {
    tx.execute(
        "INSERT INTO sync_pulled_origins VALUES(?1,?2,?3,?4,?5)
         ON CONFLICT(record_id) DO UPDATE SET local_revision=excluded.local_revision,
             profile_id=excluded.profile_id,key_id=excluded.key_id,
             material_sha256=excluded.material_sha256",
        params![
            record.id.to_string(),
            record.revision as i64,
            binding.profile_id.to_string(),
            binding.key_id.to_string(),
            hex::encode(binding.material_sha256),
        ],
    )?;
    Ok(())
}

pub(crate) fn require_local_mutation(
    tx: &Transaction<'_>,
    binding: &WorkspaceBinding,
    pending: &super::sync::PendingOperation,
) -> Result<(), Error> {
    let available: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM sqlite_schema WHERE type='table' AND name='sync_local_origins')",
        [],
        |row| row.get(0),
    )?;
    if !available {
        return Err(Error::SyncOrigin);
    }
    let known: bool = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM sync_local_origins
         WHERE operation_id=?1 AND record_id=?2 AND local_revision=?3
           AND profile_id=?4 AND key_id=?5 AND material_sha256=?6)",
        params![
            pending.operation_id.to_string(),
            pending.record.id.to_string(),
            pending.record.revision as i64,
            binding.profile_id.to_string(),
            binding.key_id.to_string(),
            hex::encode(binding.material_sha256),
        ],
        |row| row.get(0),
    )?;
    if !known {
        return Err(Error::SyncOrigin);
    }
    Ok(())
}
