// SPDX-License-Identifier: GPL-3.0-only
//! Transactional storage boundary for already authenticated pull records.
//! Hosts must use the crypto-verifying sync layer, never raw server JSON here.
use crate::{
    sync::{acknowledge_in_tx, require_profile, server_revision, UploadOperation},
    Error, Record, Store,
};
use rusqlite::{params, OptionalExtension, TransactionBehavior};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub struct RemoteRecord {
    pub publisher_id: Uuid,
    pub revision: u64,
    pub operation: UploadOperation,
    pub record: Record,
}
#[derive(Debug, Default, PartialEq, Eq)]
pub struct ApplyReport {
    pub cursor: u64,
    pub applied: usize,
    pub echoed: usize,
    pub unchanged: usize,
}
impl Store {
    /// All records MUST already have passed AEAD and outer/body binding checks.
    /// A conflict rejects the entire page and retains local edits and cursor.
    /// This initial boundary stops on conflicts; it does not resolve them.
    pub fn apply_verified_pull(
        &mut self,
        profile: Uuid,
        after: u64,
        next: u64,
        head: u64,
        changes: &[RemoteRecord],
    ) -> Result<ApplyReport, Error> {
        if after > i64::MAX as u64
            || head > i64::MAX as u64
            || changes.len() > 100
            || after.checked_add(changes.len() as u64) != Some(next)
            || next > head
            || (changes.is_empty() && head != after)
        {
            return Err(Error::SyncHistory);
        }
        for (index, change) in changes.iter().enumerate() {
            change.operation.validate()?;
            let record = &change.record;
            if change.publisher_id.is_nil()
                || change.revision != after + index as u64 + 1
                || change.operation.base_revision >= change.revision
                || record.id != change.operation.record_id
                || record.id.is_nil()
                || record.device_id.is_nil()
                || record.revision == 0
                || record.revision > i64::MAX as u64
                || record.content.kind() != change.operation.kind
                || record.deleted != change.operation.deleted
            {
                return Err(Error::SyncHistory);
            }
            record.content.validate()?;
        }
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        require_profile(&tx, profile)?;
        let saved: String = tx.query_row(
            "SELECT value FROM metadata WHERE key='sync_cursor'",
            [],
            |r| r.get(0),
        )?;
        if saved.parse::<u64>().map_err(|_| Error::Invalid)? != after {
            return Err(Error::SyncCursor);
        }
        let mut report = ApplyReport {
            cursor: next,
            ..ApplyReport::default()
        };
        for change in changes {
            let op = &change.operation;
            let received: Option<String> = tx
                .query_row(
                    "SELECT fingerprint FROM sync_received WHERE operation_id=?1",
                    [op.operation_id.to_string()],
                    |r| r.get(0),
                )
                .optional()?;
            // Never let an old authenticated operation resurrect a record under
            // a newly assigned outer revision. Cursor alone is insufficient.
            if received.is_some() {
                return Err(Error::SyncHistory);
            }
            let canonical: Option<String> = tx
                .query_row(
                    "SELECT body FROM records WHERE id=?1",
                    [op.record_id.to_string()],
                    |r| r.get(0),
                )
                .optional()?;
            let current: Option<Record> = canonical
                .map(|body| serde_json::from_str(&body))
                .transpose()?;
            let known = server_revision(&tx, op.record_id)?;
            let local_operation: Option<String> = tx
                .query_row(
                    "SELECT result FROM operations WHERE id=?1",
                    [op.operation_id.to_string()],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(snapshot) = local_operation {
                let original: Record = serde_json::from_str(&snapshot)?;
                let staged: Option<(String, Option<i64>)> = tx
                    .query_row(
                        "SELECT wire,ack_revision FROM sync_uploads WHERE operation_id=?1",
                        [op.operation_id.to_string()],
                        |r| Ok((r.get(0)?, r.get(1)?)),
                    )
                    .optional()?;
                let Some((wire, ack)) = staged else {
                    return Err(Error::SyncHistory);
                };
                let frozen: UploadOperation = serde_json::from_str(&wire)?;
                if frozen != *op || original != change.record || current.is_none() {
                    return Err(Error::SyncHistory);
                }
                if ack.is_some_and(|revision| revision < 0 || known < revision as u64) {
                    return Err(Error::SyncHistory);
                }
                acknowledge_in_tx(&tx, op.operation_id, change.revision)?;
                report.echoed += 1;
            } else if change.revision <= known {
                // A newer local upload may have been acknowledged before its
                // preceding account history is pulled. Do not regress it.
                if current.is_none() {
                    return Err(Error::SyncHistory);
                }
                report.unchanged += 1;
            } else {
                if op.base_revision != known {
                    return Err(Error::SyncHistory);
                }
                let pending:Option<i64>=tx.query_row("SELECT 1 FROM operations WHERE pending=1 AND json_extract(result,'$.id')=?1 LIMIT 1",[op.record_id.to_string()],|r|r.get(0)).optional()?;
                if pending.is_some() {
                    return Err(Error::SyncLocalConflict);
                }
                let adopt = current.as_ref().is_some_and(|local| {
                    known == 0
                        && local.content == change.record.content
                        && local.deleted == change.record.deleted
                });
                if current
                    .as_ref()
                    .is_some_and(|local| local.content.kind() != op.kind || (known == 0 && !adopt))
                {
                    return Err(Error::SyncLocalConflict);
                }
                if adopt {
                    report.unchanged += 1;
                } else {
                    if !change.record.deleted {
                        Self::validate_unique(&tx, op.record_id, &change.record.content)?;
                    }
                    let revision = current
                        .as_ref()
                        .map(|record| record.revision)
                        .unwrap_or(0)
                        .checked_add(1)
                        .filter(|r| *r <= i64::MAX as u64)
                        .ok_or(Error::Invalid)?;
                    let mut record = change.record.clone();
                    record.revision = revision;
                    tx.execute("INSERT INTO records VALUES(?1,?2,?3) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,body=excluded.body",params![record.id.to_string(),revision as i64,serde_json::to_string(&record)?])?;
                    // Applying a download never enqueues a new upload.
                    report.applied += 1;
                }
                tx.execute("INSERT INTO sync_versions VALUES(?1,?2) ON CONFLICT(record_id) DO UPDATE SET server_revision=excluded.server_revision",params![op.record_id.to_string(),change.revision as i64])?;
            }
            let fingerprint = hex::encode(Sha256::digest(serde_json::to_vec(&(
                profile,
                change.publisher_id,
                change.revision,
                op,
                &change.record,
            ))?));
            tx.execute(
                "INSERT INTO sync_received VALUES(?1,?2,?3)",
                params![
                    op.operation_id.to_string(),
                    change.revision as i64,
                    fingerprint
                ],
            )?;
        }
        tx.execute(
            "UPDATE metadata SET value=?1 WHERE key='sync_cursor'",
            [next.to_string()],
        )?;
        tx.commit()?;
        Ok(report)
    }
}
