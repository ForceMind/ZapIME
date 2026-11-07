// SPDX-License-Identifier: GPL-3.0-only
//! 持久化待发快照与密文请求。事务下载应用见 pull；HTTP 与冲突解决尚未接入。
use crate::{Error, Record, Store};
use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant};
use uuid::Uuid;

#[derive(Debug, PartialEq, Eq, Serialize)]
pub struct ProfileStatus {
    pub initialized: bool,
    pub account_id: Option<Uuid>,
}

/// Non-secret identity of the locally prepared key material. This is not a
/// server registration or evidence that another device uses the same key.
#[derive(Clone, PartialEq, Eq)]
pub struct WorkspaceBinding {
    pub profile_id: Uuid,
    pub key_id: Uuid,
    pub material_sha256: [u8; 32],
}

fn workspace_budget<T>(
    db: &mut rusqlite::Connection,
    work: impl FnOnce(&mut rusqlite::Connection) -> Result<T, Error>,
) -> Result<T, Error> {
    // A cooperative SQLite VM budget, not a hard disk-I/O deadline. These
    // methods use an independent sync connection; never the input connection.
    let started = Instant::now();
    db.progress_handler(
        1000,
        Some(move || started.elapsed() >= Duration::from_millis(200)),
    )?;
    let result = work(db);
    db.progress_handler(0, None::<fn() -> bool>)?;
    result
}

fn canonical_id(value: &str) -> Result<Uuid, Error> {
    let id = Uuid::parse_str(value).map_err(|_| Error::SyncWorkspace)?;
    if id.is_nil() || id.to_string() != value {
        return Err(Error::SyncWorkspace);
    }
    Ok(id)
}

/// Must be called inside the same transaction as preparation. Ordinary local
/// operations/queue entries are allowed; evidence of an old sync relationship
/// without the complete workspace identity is not silently adopted.
fn workspace_binding(tx: &Transaction<'_>) -> Result<Option<WorkspaceBinding>, Error> {
    if tx.query_row::<u32, _, _>("PRAGMA user_version", [], |row| row.get(0))? != 2 {
        return Err(Error::Version);
    }
    let mut statement = tx.prepare(
        "SELECT CASE WHEN length(CAST(key AS BLOB))<=64 THEN key END,
                CASE WHEN length(CAST(value AS BLOB))<=128 THEN value END
         FROM metadata WHERE substr(key,1,5)='sync_' ORDER BY key LIMIT 5",
    )?;
    let rows = statement
        .query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    let mut values = std::collections::BTreeMap::new();
    for (key, value) in rows {
        if values.insert(key, value).is_some() {
            return Err(Error::SyncWorkspace);
        }
    }
    if values.is_empty() {
        let history: i64 = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM sync_uploads) OR EXISTS(SELECT 1 FROM sync_versions)
             OR EXISTS(SELECT 1 FROM sync_received) OR EXISTS(SELECT 1 FROM sync_retained_pulls)
             OR EXISTS(SELECT 1 FROM operations WHERE pending<>1)",
            [],
            |row| row.get(0),
        )?;
        if history != 0 {
            return Err(Error::SyncWorkspace);
        }
        return Ok(None);
    }
    if values.len() != 4 {
        return Err(Error::SyncWorkspace);
    }
    let get = |name: &str| values.get(name).ok_or(Error::SyncWorkspace);
    let profile_id = canonical_id(get("sync_profile")?)?;
    let key_id = canonical_id(get("sync_key_id")?)?;
    let cursor = get("sync_cursor")?;
    let parsed: u64 = cursor.parse().map_err(|_| Error::SyncWorkspace)?;
    if parsed > i64::MAX as u64 || parsed.to_string() != *cursor {
        return Err(Error::SyncWorkspace);
    }
    let fingerprint = get("sync_material_sha256")?;
    if fingerprint.len() != 64
        || !fingerprint
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err(Error::SyncWorkspace);
    }
    let material_sha256: [u8; 32] = hex::decode(fingerprint)
        .map_err(|_| Error::SyncWorkspace)?
        .try_into()
        .map_err(|_| Error::SyncWorkspace)?;
    // A prepared single-key workspace cannot adopt foreign/legacy frozen keys
    // or malformed receipt state. Acknowledged revisions may legitimately be
    // ahead of pull cursor; received revisions may not.
    let invalid: i64 = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM sync_uploads s LEFT JOIN operations o ON o.id=s.operation_id
           WHERE o.id IS NULL OR CASE WHEN json_valid(s.wire) THEN
               json_type(s.wire,'$.key_id') IS NOT 'text'
               OR json_extract(s.wire,'$.key_id')<>?1
               OR json_extract(s.wire,'$.operation_id') IS NOT s.operation_id
             ELSE 1 END
           OR (s.ack_revision IS NULL AND o.pending<>1)
           OR (s.ack_revision IS NOT NULL AND (typeof(s.ack_revision)<>'integer' OR s.ack_revision<=0 OR o.pending<>0)))
         OR EXISTS(SELECT 1 FROM sync_versions WHERE typeof(server_revision)<>'integer' OR server_revision<=0)
         OR EXISTS(SELECT 1 FROM sync_received WHERE typeof(server_revision)<>'integer' OR server_revision<=0 OR server_revision>?2)",
        params![key_id.to_string(), parsed as i64],
        |row| row.get(0),
    )?;
    if invalid != 0 {
        return Err(Error::SyncWorkspace);
    }
    Ok(Some(WorkspaceBinding {
        profile_id,
        key_id,
        material_sha256,
    }))
}

/// Inspect only an existing v2 database. Never initialize, migrate or bind it.
pub fn inspect_profile(path: &std::path::Path) -> Result<ProfileStatus, Error> {
    match std::fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(ProfileStatus {
                initialized: false,
                account_id: None,
            });
        }
        Ok(metadata) if metadata.file_type().is_file() => {}
        _ => return Err(Error::Invalid),
    }
    let store = Store::open_panel_reader(path)?;
    Ok(ProfileStatus {
        initialized: true,
        account_id: store.sync_profile()?,
    })
}

#[derive(Clone)]
pub struct PendingOperation {
    pub operation_id: Uuid,
    /// 创建该操作时的不可变快照；不能换成 records 中已经继续编辑的新版本。
    pub record: Record,
    pub server_revision: u64,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CipherEnvelope {
    pub version: u8,
    pub nonce: String,
    pub ciphertext: String,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UploadOperation {
    pub operation_id: Uuid,
    pub record_id: Uuid,
    pub key_id: Uuid,
    pub kind: u8,
    pub base_revision: u64,
    pub deleted: bool,
    pub envelope: CipherEnvelope,
}
impl UploadOperation {
    pub(crate) fn validate(&self) -> Result<(), Error> {
        let base64url = |s: &str| {
            s.bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
        };
        if self.operation_id.is_nil()
            || self.record_id.is_nil()
            || self.key_id.is_nil()
            || !(1..=5).contains(&self.kind)
            || self.base_revision > i64::MAX as u64
            || self.envelope.version != 1
            || self.envelope.nonce.len() != 32
            || !(22..=87403).contains(&self.envelope.ciphertext.len())
            || !base64url(&self.envelope.nonce)
            || !base64url(&self.envelope.ciphertext)
        {
            return Err(Error::Invalid);
        }
        Ok(())
    }
    pub fn to_wire(&self) -> Result<Vec<u8>, Error> {
        self.validate()?;
        Ok(serde_json::to_vec(self)?)
    }
    fn matches_snapshot(&self, operation: Uuid, record: &Record) -> Result<(), Error> {
        self.validate()?;
        if self.operation_id != operation
            || self.record_id != record.id
            || self.kind != record.content.kind()
            || self.deleted != record.deleted
        {
            return Err(Error::Invalid);
        }
        Ok(())
    }
}

pub(crate) fn require_profile(tx: &Transaction<'_>, profile: Uuid) -> Result<(), Error> {
    let value: Option<String> = tx
        .query_row(
            "SELECT value FROM metadata WHERE key='sync_profile'",
            [],
            |r| r.get(0),
        )
        .optional()?;
    if profile.is_nil() || value.as_deref() != Some(profile.to_string().as_str()) {
        return Err(Error::SyncProfile);
    }
    Ok(())
}
pub(crate) fn server_revision(tx: &Transaction<'_>, record: Uuid) -> Result<u64, Error> {
    let revision: i64 = tx
        .query_row(
            "SELECT server_revision FROM sync_versions WHERE record_id=?1",
            [record.to_string()],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(0);
    u64::try_from(revision).map_err(|_| Error::Invalid)
}

impl Store {
    /// Read one consistent identity snapshot; never prepares, migrates or
    /// changes pending operations. Caller chooses an existing read-only store.
    pub fn sync_workspace_binding(&mut self) -> Result<Option<WorkspaceBinding>, Error> {
        workspace_budget(&mut self.db, |db| {
            let tx = db.transaction()?;
            workspace_binding(&tx)
        })
    }
    /// Explicit local preparation, after the platform vault has committed and
    /// read back this material. Only metadata is written; no network, queue
    /// coalescing, record mutation, upload or bare cursor advancement occurs.
    pub fn prepare_sync_workspace(&mut self, binding: &WorkspaceBinding) -> Result<(), Error> {
        if binding.profile_id.is_nil() || binding.key_id.is_nil() {
            return Err(Error::Invalid);
        }
        workspace_budget(&mut self.db, |db| {
            let tx = db.transaction_with_behavior(TransactionBehavior::Immediate)?;
            if let Some(existing) = workspace_binding(&tx)? {
                if existing != *binding {
                    return Err(Error::SyncWorkspace);
                }
            } else {
                tx.execute(
                    "INSERT INTO metadata VALUES('sync_profile',?1)",
                    [binding.profile_id.to_string()],
                )?;
                tx.execute("INSERT INTO metadata VALUES('sync_cursor','0')", [])?;
                tx.execute(
                    "INSERT INTO metadata VALUES('sync_key_id',?1)",
                    [binding.key_id.to_string()],
                )?;
                tx.execute(
                    "INSERT INTO metadata VALUES('sync_material_sha256',?1)",
                    [hex::encode(binding.material_sha256)],
                )?;
            }
            tx.commit()?;
            Ok(())
        })
    }
    /// No binding is different from an unreadable or noncanonical binding.
    pub fn sync_profile(&self) -> Result<Option<Uuid>, Error> {
        let value: Option<String> = self
            .db
            .query_row(
                "SELECT value FROM metadata WHERE key='sync_profile'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        value
            .map(|text| {
                let profile = Uuid::parse_str(&text).map_err(|_| Error::SyncProfile)?;
                if profile.is_nil() || profile.to_string() != text {
                    return Err(Error::SyncProfile);
                }
                Ok(profile)
            })
            .transpose()
    }
    /// Best-effort net-change compaction before encryption/staging. Within the
    /// oldest bounded window, only the last unstaged operation per record stays
    /// pending. Frozen requests and all immutable snapshots remain untouched.
    /// The transport MUST stage every operation before any network attempt.
    pub fn coalesce_unstaged(&mut self, profile: Uuid, limit: usize) -> Result<usize, Error> {
        if !(1..=4096).contains(&limit) {
            return Err(Error::Invalid);
        }
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        require_profile(&tx, profile)?;
        let changed = tx.execute(
            "WITH window AS MATERIALIZED (
                SELECT o.id AS operation_id,json_extract(o.result,'$.id') AS record_id,q.sequence
                FROM sync_queue q JOIN operations o ON o.id=q.operation_id
                WHERE o.pending=1 AND NOT EXISTS(SELECT 1 FROM sync_uploads s WHERE s.operation_id=o.id)
                ORDER BY q.sequence LIMIT ?1
             ), ranked AS (
                SELECT operation_id,row_number() OVER(PARTITION BY record_id ORDER BY sequence DESC) AS position FROM window
             ) UPDATE operations SET pending=0 WHERE id IN(SELECT operation_id FROM ranked WHERE position>1)",
            [limit as i64],
        )?;
        tx.commit()?;
        Ok(changed)
    }
    /// 同一资料库只绑定一个账号。换服务器可保留账号 UUID，换账号须显式迁移到新库。
    pub fn bind_sync_profile(&mut self, profile: Uuid) -> Result<(), Error> {
        if profile.is_nil() {
            return Err(Error::Invalid);
        }
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        tx.execute(
            "INSERT OR IGNORE INTO metadata VALUES('sync_profile',?1)",
            [profile.to_string()],
        )?;
        require_profile(&tx, profile)?;
        tx.execute(
            "INSERT OR IGNORE INTO metadata VALUES('sync_cursor','0')",
            [],
        )?;
        tx.commit()?;
        Ok(())
    }
    pub fn sync_cursor(&mut self, profile: Uuid) -> Result<u64, Error> {
        let tx = self.db.transaction()?;
        require_profile(&tx, profile)?;
        let value: String = tx.query_row(
            "SELECT value FROM metadata WHERE key='sync_cursor'",
            [],
            |r| r.get(0),
        )?;
        value.parse().map_err(|_| Error::Invalid)
    }
    pub fn pending_operations(
        &mut self,
        profile: Uuid,
        limit: usize,
    ) -> Result<Vec<PendingOperation>, Error> {
        if !(1..=256).contains(&limit) {
            return Err(Error::Invalid);
        }
        let tx = self.db.transaction()?;
        require_profile(&tx, profile)?;
        let mut stmt =
            tx.prepare("SELECT o.id,o.result FROM operations o JOIN sync_queue q ON q.operation_id=o.id WHERE o.pending=1 ORDER BY q.sequence LIMIT ?1")?;
        let mut pending = Vec::new();
        for row in stmt.query_map([limit as i64], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })? {
            let (id, result) = row?;
            let record: Record = serde_json::from_str(&result)?;
            let operation_id = Uuid::parse_str(&id).map_err(|_| Error::Invalid)?;
            let revision = server_revision(&tx, record.id)?;
            pending.push(PendingOperation {
                operation_id,
                record,
                server_revision: revision,
            });
        }
        Ok(pending)
    }
    /// 调用方先用 zapime-crypto 认证加密，再持久化请求；重试原样返回首次请求，不能重新随机加密。
    pub fn stage_upload(
        &mut self,
        profile: Uuid,
        upload: UploadOperation,
    ) -> Result<UploadOperation, Error> {
        upload.validate()?;
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        require_profile(&tx, profile)?;
        let first: Option<(String, String)> = tx
            .query_row(
                "SELECT o.id,o.result FROM operations o JOIN sync_queue q ON q.operation_id=o.id WHERE o.pending=1 ORDER BY q.sequence LIMIT 1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let Some((id, result)) = first else {
            return Err(Error::SyncOrder);
        };
        if id != upload.operation_id.to_string() {
            return Err(Error::SyncOrder);
        }
        let record: Record = serde_json::from_str(&result)?;
        upload.matches_snapshot(upload.operation_id, &record)?;
        let revision = server_revision(&tx, record.id)?;
        if upload.base_revision != revision {
            return Err(Error::Conflict);
        }
        let previous: Option<String> = tx
            .query_row(
                "SELECT wire FROM sync_uploads WHERE operation_id=?1",
                [id.clone()],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(wire) = previous {
            // 首次 nonce/密钥版本/正文属于该操作；密钥轮换不能暗中替换待发请求。
            let saved: UploadOperation = serde_json::from_str(&wire)?;
            saved.matches_snapshot(upload.operation_id, &record)?;
            if saved.base_revision != revision {
                return Err(Error::Conflict);
            }
            return Ok(saved);
        }
        let wire = String::from_utf8(upload.to_wire()?).map_err(|_| Error::Invalid)?;
        tx.execute(
            "INSERT INTO sync_uploads VALUES(?1,?2,NULL)",
            params![id, wire],
        )?;
        tx.commit()?;
        Ok(upload)
    }
    /// 仅接收已验证账号、操作和内容匹配的服务端响应。确认不推进拉取游标，也不覆盖较新的本地记录。
    pub fn acknowledge_upload(
        &mut self,
        profile: Uuid,
        operation: Uuid,
        revision: u64,
    ) -> Result<(), Error> {
        if operation.is_nil() || revision == 0 || revision > i64::MAX as u64 {
            return Err(Error::SyncAck);
        }
        let tx = self
            .db
            .transaction_with_behavior(TransactionBehavior::Immediate)?;
        require_profile(&tx, profile)?;
        acknowledge_in_tx(&tx, operation, revision)?;
        tx.commit()?;
        Ok(())
    }
}

pub(crate) fn acknowledge_in_tx(
    tx: &Transaction<'_>,
    operation: Uuid,
    revision: u64,
) -> Result<(), Error> {
    if operation.is_nil() || revision == 0 || revision > i64::MAX as u64 {
        return Err(Error::SyncAck);
    }
    let staged: Option<(String, Option<i64>)> = tx
        .query_row(
            "SELECT wire,ack_revision FROM sync_uploads WHERE operation_id=?1",
            [operation.to_string()],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let Some((wire, acknowledged)) = staged else {
        return Err(Error::SyncAck);
    };
    if let Some(old) = acknowledged {
        return if old == revision as i64 {
            Ok(())
        } else {
            Err(Error::SyncAck)
        };
    }
    let upload: UploadOperation = serde_json::from_str(&wire)?;
    let snapshot: String = tx.query_row(
        "SELECT result FROM operations WHERE id=?1",
        [operation.to_string()],
        |r| r.get(0),
    )?;
    let record: Record = serde_json::from_str(&snapshot)?;
    upload.matches_snapshot(operation, &record)?;
    if revision <= upload.base_revision
        || server_revision(tx, upload.record_id)? != upload.base_revision
    {
        return Err(Error::SyncAck);
    }
    tx.execute("INSERT INTO sync_versions VALUES(?1,?2) ON CONFLICT(record_id) DO UPDATE SET server_revision=excluded.server_revision", params![upload.record_id.to_string(), revision as i64])?;
    let count = tx.execute(
        "UPDATE operations SET pending=0 WHERE id=?1 AND pending=1",
        [operation.to_string()],
    )?;
    if count != 1 {
        return Err(Error::SyncAck);
    }
    tx.execute(
        "UPDATE sync_uploads SET ack_revision=?2 WHERE operation_id=?1",
        params![operation.to_string(), revision as i64],
    )?;
    Ok(())
}
