// SPDX-License-Identifier: GPL-3.0-only
//! 持久化待发快照与密文请求。事务下载应用见 pull；HTTP 与冲突解决尚未接入。
use crate::{Error, Record, Store};
use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

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
