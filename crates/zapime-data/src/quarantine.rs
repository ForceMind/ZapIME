// SPDX-License-Identifier: GPL-3.0-only
//! Bounded, persistent untrusted ciphertext journal. Not conflict resolution.
use crate::{sync::require_profile, Error, Store};
use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub const MAX_PAGE_BYTES: usize = 10 * 1024 * 1024;
const MAX_RETAINED_BYTES: usize = 32 * 1024 * 1024;
const MAX_RETAINED_PAGES: i64 = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureKind {
    MissingKey,
    Authentication,
    LocalConflict,
    History,
    InvalidPayload,
}
#[derive(Debug, PartialEq, Eq)]
pub struct RetainedSummary {
    pub id: String,
    pub sequence: u64,
    pub after_cursor: u64,
    pub reason: FailureKind,
    pub bytes: usize,
}
// Deliberately no Debug: never accidentally log the wire body.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RetainedPull {
    pub version: u8,
    pub profile_id: Uuid,
    pub after_cursor: u64,
    pub reason: FailureKind,
    pub digest: String,
    pub wire: String,
}
pub(crate) fn prepare(tx: &Transaction<'_>) -> Result<(), Error> {
    // Additive auxiliary state, compatible with v2 readers/writers. No record,
    // operation, frozen request or cursor migration/rebuild is necessary.
    tx.execute_batch(
        "CREATE TABLE IF NOT EXISTS sync_retained_pulls(
        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
        id TEXT UNIQUE NOT NULL,profile_id TEXT NOT NULL,after_cursor INTEGER NOT NULL,
        reason TEXT NOT NULL,wire TEXT NOT NULL);",
    )?;
    Ok(())
}
fn fingerprint(profile: Uuid, after: u64, wire: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"ZapIME retained pull v1\0");
    hash.update(profile.as_bytes());
    hash.update(after.to_le_bytes());
    hash.update(wire.as_bytes());
    hex::encode(hash.finalize())
}
fn valid_id(id: &str) -> bool {
    id.len() == 64
        && id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn reason_from_sql(value: String) -> Result<FailureKind, Error> {
    Ok(serde_json::from_str(&value)?)
}
impl RetainedPull {
    pub fn validate_integrity(&self) -> Result<(), Error> {
        if self.version != 1 {
            return Err(Error::Version);
        }
        if self.profile_id.is_nil()
            || self.after_cursor > i64::MAX as u64
            || self.wire.is_empty()
            || self.wire.len() > MAX_PAGE_BYTES
            || !valid_id(&self.digest)
            || fingerprint(self.profile_id, self.after_cursor, &self.wire) != self.digest
        {
            return Err(Error::Integrity);
        }
        Ok(())
    }
}
impl Store {
    /// Trusted caller must first validate strict ciphertext-only page shape and
    /// account/continuity. This does not authenticate or apply the body.
    pub fn retain_failed_pull(
        &mut self,
        profile: Uuid,
        after: u64,
        reason: FailureKind,
        wire: &str,
    ) -> Result<RetainedSummary, Error> {
        if after > i64::MAX as u64 || wire.is_empty() || wire.len() > MAX_PAGE_BYTES {
            return Err(Error::Invalid);
        }
        let id = fingerprint(profile, after, wire);
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
        let old: Option<(i64, String, String, String, i64)> = tx.query_row("SELECT sequence,reason,wire,profile_id,after_cursor FROM sync_retained_pulls WHERE id=?1", [&id], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))).optional()?;
        let (sequence, first_reason) = if let Some((
            sequence,
            old_reason,
            old_wire,
            old_profile,
            old_after,
        )) = old
        {
            if sequence < 1
                || old_wire != wire
                || old_profile != profile.to_string()
                || old_after != after as i64
            {
                return Err(Error::Integrity);
            }
            // Repeated failure does not update bytes, timestamps or counters.
            (sequence, reason_from_sql(old_reason)?)
        } else {
            let (count, bytes): (i64, i64) = tx.query_row("SELECT count(*),coalesce(sum(length(CAST(wire AS BLOB))),0) FROM sync_retained_pulls", [], |r| Ok((r.get(0)?, r.get(1)?)))?;
            if count >= MAX_RETAINED_PAGES
                || bytes < 0
                || bytes as usize > MAX_RETAINED_BYTES - wire.len()
            {
                return Err(Error::RetainedFull);
            }
            tx.execute("INSERT INTO sync_retained_pulls(id,profile_id,after_cursor,reason,wire) VALUES(?1,?2,?3,?4,?5)", params![id, profile.to_string(), after as i64, serde_json::to_string(&reason)?, wire])?;
            (tx.last_insert_rowid(), reason)
        };
        tx.commit()?;
        Ok(RetainedSummary {
            id,
            sequence: sequence as u64,
            after_cursor: after,
            reason: first_reason,
            bytes: wire.len(),
        })
    }
    /// Summaries only: SQL paging must not materialize every retained body.
    pub fn retained_pulls(
        &mut self,
        profile: Uuid,
        after_sequence: u64,
        limit: usize,
    ) -> Result<Vec<RetainedSummary>, Error> {
        if after_sequence > i64::MAX as u64 || !(1..=20).contains(&limit) {
            return Err(Error::Invalid);
        }
        let tx = self.db.transaction()?;
        require_profile(&tx, profile)?;
        let mut statement = tx.prepare("SELECT id,sequence,after_cursor,reason,length(CAST(wire AS BLOB)) FROM sync_retained_pulls WHERE profile_id=?1 AND sequence>?2 ORDER BY sequence LIMIT ?3")?;
        let mut summaries = Vec::new();
        for row in statement.query_map(
            params![profile.to_string(), after_sequence as i64, limit as i64],
            |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, String>(3)?,
                    r.get::<_, i64>(4)?,
                ))
            },
        )? {
            let (id, sequence, after, reason, bytes) = row?;
            if !valid_id(&id)
                || sequence < 1
                || after < 0
                || !(1..=MAX_PAGE_BYTES as i64).contains(&bytes)
            {
                return Err(Error::Integrity);
            }
            summaries.push(RetainedSummary {
                id,
                sequence: sequence as u64,
                after_cursor: after as u64,
                reason: reason_from_sql(reason)?,
                bytes: bytes as usize,
            });
        }
        Ok(summaries)
    }
    pub fn retained_pull(
        &mut self,
        profile: Uuid,
        id: &str,
    ) -> Result<Option<RetainedPull>, Error> {
        if !valid_id(id) {
            return Err(Error::Invalid);
        }
        let tx = self.db.transaction()?;
        require_profile(&tx, profile)?;
        let found: Option<(i64, String, String)> = tx.query_row("SELECT after_cursor,reason,wire FROM sync_retained_pulls WHERE id=?1 AND profile_id=?2", params![id, profile.to_string()], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).optional()?;
        let Some((after, reason, wire)) = found else {
            return Ok(None);
        };
        let pull = RetainedPull {
            version: 1,
            profile_id: profile,
            after_cursor: after as u64,
            reason: reason_from_sql(reason)?,
            digest: id.to_owned(),
            wire,
        };
        pull.validate_integrity()?;
        Ok(Some(pull))
    }
    /// Explicit user action, never automatic TTL/eviction or a cursor advance.
    pub fn discard_retained_pull(&mut self, profile: Uuid, id: &str) -> Result<bool, Error> {
        if !valid_id(id) {
            return Err(Error::Invalid);
        }
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        require_profile(&tx, profile)?;
        let changed = tx.execute(
            "DELETE FROM sync_retained_pulls WHERE id=?1 AND profile_id=?2",
            params![id, profile.to_string()],
        )?;
        tx.commit()?;
        Ok(changed == 1)
    }
}
