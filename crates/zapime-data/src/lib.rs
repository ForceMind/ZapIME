// SPDX-License-Identifier: GPL-3.0-only
//! 四端共享的本地个人数据。修订用于并发校验，时间不决定覆盖胜负。
#![forbid(unsafe_code)]
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::HashSet, path::Path};
use uuid::Uuid;

const MAX_ARCHIVE_BYTES: usize = 32 * 1024 * 1024;
const MAX_RECORDS: usize = 100_000;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("storage_error")]
    Storage(#[from] rusqlite::Error),
    #[error("invalid_archive")]
    Json(#[from] serde_json::Error),
    #[error("invalid_record")]
    Invalid,
    #[error("revision_conflict")]
    Conflict,
    #[error("operation_id_reused")]
    OperationReused,
    #[error("unsupported_schema_version")]
    Version,
    #[error("archive_integrity_failed")]
    Integrity,
    #[error("restore_requires_empty_profile")]
    NotEmpty,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum Content {
    Dictionary {
        scheme: String,
        code: String,
        text: String,
        weight: i64,
    },
    CandidateRule {
        scheme: String,
        code: String,
        text: String,
        position: u8,
    },
    Snippet {
        code: String,
        text: String,
        tags: Vec<String>,
    },
    Favorite {
        title: String,
        text: String,
    },
    Clipboard {
        text: String,
        expires_at_ms: u64,
        pinned: bool,
    },
}
impl Content {
    fn validate(&self) -> Result<(), Error> {
        let (text, code) = match self {
            Self::Dictionary {
                scheme, code, text, ..
            }
            | Self::CandidateRule {
                scheme, code, text, ..
            } => {
                if scheme != "pinyin" && scheme != "english" {
                    return Err(Error::Invalid);
                }
                (text, Some(code))
            }
            Self::Snippet { code, text, tags } => {
                if tags.len() > 32 || tags.iter().any(|t| t.len() > 128) {
                    return Err(Error::Invalid);
                }
                (text, Some(code))
            }
            Self::Favorite { title, text } => {
                if title.len() > 1024 {
                    return Err(Error::Invalid);
                }
                (text, None)
            }
            Self::Clipboard {
                text,
                expires_at_ms,
                ..
            } => {
                if *expires_at_ms == 0 {
                    return Err(Error::Invalid);
                }
                (text, None)
            }
        };
        if text.is_empty() || text.len() > 65536 || text.contains('\0') {
            return Err(Error::Invalid);
        }
        if let Some(code) = code {
            if code.is_empty()
                || code.len() > 128
                || !code
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b == b'\'' || b.is_ascii_digit())
            {
                return Err(Error::Invalid);
            }
        }
        if let Self::CandidateRule { position, .. } = self {
            if !(1..=40).contains(position) {
                return Err(Error::Invalid);
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Record {
    pub id: Uuid,
    pub revision: u64,
    pub device_id: Uuid,
    pub deleted: bool,
    pub content: Content,
}
#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ArchiveBody {
    schema_version: u32,
    records: Vec<Record>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Archive {
    body: ArchiveBody,
    sha256: String,
}

pub struct Store {
    db: Connection,
}
impl Store {
    pub fn open(path: &Path) -> Result<Self, Error> {
        let db = Connection::open(path)?;
        db.busy_timeout(std::time::Duration::from_secs(2))?;
        db.execute_batch("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;")?;
        let version: u32 = db.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if version > 1 {
            return Err(Error::Version);
        }
        db.execute_batch("BEGIN IMMEDIATE;
            CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY, request TEXT NOT NULL, result TEXT NOT NULL, pending INTEGER NOT NULL);
            PRAGMA user_version=1; COMMIT;")?;
        Ok(Self { db })
    }
    pub fn get(&self, id: Uuid) -> Result<Option<Record>, Error> {
        let value: Option<String> = self
            .db
            .query_row(
                "SELECT body FROM records WHERE id=?1",
                [id.to_string()],
                |r| r.get(0),
            )
            .optional()?;
        value
            .map(|s| serde_json::from_str(&s).map_err(Error::from))
            .transpose()
    }
    /// 写记录和离线操作为同一事务；重复操作只在请求字节一致时幂等。
    pub fn edit(
        &mut self,
        operation_id: Uuid,
        id: Uuid,
        expected_revision: u64,
        device_id: Uuid,
        content: Content,
        deleted: bool,
    ) -> Result<Record, Error> {
        content.validate()?;
        if operation_id.is_nil() || id.is_nil() || device_id.is_nil() {
            return Err(Error::Invalid);
        }
        let request =
            serde_json::to_string(&(id, expected_revision, device_id, &content, deleted))?;
        let tx = self
            .db
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let previous: Option<(String, String)> = tx
            .query_row(
                "SELECT request,result FROM operations WHERE id=?1",
                [operation_id.to_string()],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        if let Some((old, result)) = previous {
            if request != old {
                return Err(Error::OperationReused);
            }
            return Ok(serde_json::from_str(&result)?);
        }
        let current: i64 = tx
            .query_row(
                "SELECT revision FROM records WHERE id=?1",
                [id.to_string()],
                |r| r.get(0),
            )
            .optional()?
            .unwrap_or(0);
        if current < 0 || current as u64 != expected_revision {
            return Err(Error::Conflict);
        }
        let revision = (current as u64)
            .checked_add(1)
            .filter(|v| *v <= i64::MAX as u64)
            .ok_or(Error::Invalid)?;
        let record = Record {
            id,
            revision,
            device_id,
            deleted,
            content,
        };
        let result = serde_json::to_string(&record)?;
        tx.execute("INSERT INTO records VALUES(?1,?2,?3) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,body=excluded.body",params![id.to_string(),revision as i64,result])?;
        tx.execute(
            "INSERT INTO operations VALUES(?1,?2,?3,1)",
            params![operation_id.to_string(), request, result],
        )?;
        tx.commit()?;
        Ok(record)
    }
    pub fn records(&self, include_deleted: bool) -> Result<Vec<Record>, Error> {
        let mut stmt = self.db.prepare("SELECT body FROM records ORDER BY id")?;
        let mut records = Vec::new();
        for value in stmt.query_map([], |r| r.get::<_, String>(0))? {
            let record: Record = serde_json::from_str(&value?)?;
            if include_deleted || !record.deleted {
                records.push(record);
            }
        }
        Ok(records)
    }
    pub fn pending_count(&self) -> Result<u64, Error> {
        let count: i64 =
            self.db
                .query_row("SELECT count(*) FROM operations WHERE pending=1", [], |r| {
                    r.get(0)
                })?;
        u64::try_from(count).map_err(|_| Error::Invalid)
    }
    /// 明文开放格式，调用方必须取得用户显式选择；不包含账号令牌或密钥。
    pub fn export_plaintext(&self) -> Result<Vec<u8>, Error> {
        let body = ArchiveBody {
            schema_version: 1,
            records: self.records(true)?,
        };
        let bytes = serde_json::to_vec(&body)?;
        let sha256 = hex::encode(Sha256::digest(&bytes));
        let archive = serde_json::to_vec(&Archive { body, sha256 })?;
        if archive.len() > MAX_ARCHIVE_BYTES {
            return Err(Error::Invalid);
        }
        Ok(archive)
    }
    /// 首版只恢复到空资料库；校验全部完成后一次事务导入，失败不覆盖已有数据。
    pub fn restore_plaintext(&mut self, bytes: &[u8]) -> Result<(), Error> {
        if bytes.len() > MAX_ARCHIVE_BYTES {
            return Err(Error::Invalid);
        }
        let archive: Archive = serde_json::from_slice(bytes)?;
        if archive.body.schema_version != 1 {
            return Err(Error::Version);
        }
        if hex::encode(Sha256::digest(serde_json::to_vec(&archive.body)?)) != archive.sha256 {
            return Err(Error::Integrity);
        }
        if archive.body.records.len() > MAX_RECORDS {
            return Err(Error::Invalid);
        }
        let mut seen = HashSet::new();
        for record in &archive.body.records {
            if record.id.is_nil()
                || record.device_id.is_nil()
                || record.revision == 0
                || record.revision > i64::MAX as u64
                || !seen.insert(record.id)
            {
                return Err(Error::Invalid);
            }
            record.content.validate()?;
        }
        let tx = self
            .db
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let count: i64 = tx.query_row("SELECT count(*) FROM records", [], |r| r.get(0))?;
        if count != 0 {
            return Err(Error::NotEmpty);
        }
        for record in &archive.body.records {
            tx.execute(
                "INSERT INTO records VALUES(?1,?2,?3)",
                params![
                    record.id.to_string(),
                    record.revision as i64,
                    serde_json::to_string(record)?
                ],
            )?;
        }
        tx.commit()?;
        Ok(())
    }
    /// 只清理临时剪贴板；永久收藏没有过期字段，不参与自动清理。
    pub fn expire_clipboard(&mut self, now_ms: u64, device_id: Uuid) -> Result<usize, Error> {
        let expired = self.records(false)?.into_iter().filter(|r|matches!(r.content,Content::Clipboard { expires_at_ms,.. } if expires_at_ms<=now_ms)).collect::<Vec<_>>();
        let mut count = 0;
        for record in expired {
            self.edit(
                Uuid::new_v4(),
                record.id,
                record.revision,
                device_id,
                record.content,
                true,
            )?;
            count += 1;
        }
        Ok(count)
    }
}
