// SPDX-License-Identifier: GPL-3.0-only
//! Consistent local snapshots and a writer that cannot initialize or migrate a database.
use crate::{Archive, ArchiveBody, Error, Record, Store, MAX_ARCHIVE_BYTES, MAX_RECORDS};
use rusqlite::{Connection, OpenFlags};
use sha2::{Digest, Sha256};
use std::{path::Path, time::Duration};

impl Store {
    pub fn open_backup_writer(path: &Path) -> Result<Self, Error> {
        let db = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        db.busy_timeout(Duration::from_millis(200))?;
        if db.query_row::<u32, _, _>("PRAGMA user_version", [], |r| r.get(0))? != 2 {
            return Err(Error::Version);
        }
        Ok(Self { db })
    }
    pub fn export_backup_snapshot(&mut self, limit: usize) -> Result<Vec<u8>, Error> {
        if !(512..=MAX_ARCHIVE_BYTES).contains(&limit) {
            return Err(Error::Invalid);
        }
        let tx = self.db.transaction()?;
        if tx.query_row::<u32, _, _>("PRAGMA user_version", [], |r| r.get(0))? != 2 {
            return Err(Error::Version);
        }
        let (count, size): (i64, i64) = tx.query_row(
            "SELECT count(*),coalesce(sum(length(CAST(body AS BLOB))),0) FROM records",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        if count < 0
            || size < 0
            || count as u64 > MAX_RECORDS as u64
            || (size as u64)
                .saturating_add(count as u64)
                .saturating_add(512)
                > limit as u64
        {
            return Err(Error::Invalid);
        }
        let mut records = Vec::new();
        {
            let mut statement = tx.prepare("SELECT body FROM records ORDER BY id")?;
            for row in statement.query_map([], |r| r.get::<_, String>(0))? {
                let record: Record = serde_json::from_str(&row?)?;
                records.push(record);
            }
        }
        let body = ArchiveBody {
            schema_version: 1,
            records,
        };
        let sha256 = hex::encode(Sha256::digest(serde_json::to_vec(&body)?));
        let wire = serde_json::to_vec(&Archive { body, sha256 })?;
        if wire.len() > limit {
            return Err(Error::Invalid);
        }
        crate::parse_archive(&wire)?;
        tx.commit()?;
        Ok(wire)
    }
}
