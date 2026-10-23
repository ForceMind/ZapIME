// SPDX-License-Identifier: GPL-3.0-only
//! SQLite filtering and indexed pagination; only bounded summaries leave SQLite.
use crate::{Error, Store};
use rusqlite::{functions::FunctionFlags, params, Connection, OpenFlags, Transaction};
use serde::Serialize;
use std::{
    path::Path,
    time::{Duration, Instant},
};
use uuid::Uuid;

const LABEL: &str = "CASE json_extract(body,'$.content.type') WHEN 'snippet' THEN json_extract(body,'$.content.code') WHEN 'favorite' THEN json_extract(body,'$.content.title') ELSE '剪贴板' END";
const ORDER: &str = "COALESCE(json_extract(body,'$.content.pinned'),0) DESC,json_extract(body,'$.content.type'),COALESCE(json_extract(body,'$.content.expires_at_ms'),0) DESC,CASE json_extract(body,'$.content.type') WHEN 'snippet' THEN json_extract(body,'$.content.code') WHEN 'favorite' THEN json_extract(body,'$.content.title') ELSE '剪贴板' END,id";
const FILTER: &str = "json_extract(body,'$.deleted')=0 AND json_extract(body,'$.content.type') IN ('snippet','favorite','clipboard') AND (?1='all' OR json_extract(body,'$.content.type')=?1) AND (json_extract(body,'$.content.type')<>'clipboard' OR json_extract(body,'$.content.expires_at_ms')>?2) AND (?3='' OR zapime_contains(json_extract(body,'$.content.text'),?3) OR zapime_contains(CASE json_extract(body,'$.content.type') WHEN 'snippet' THEN json_extract(body,'$.content.code') WHEN 'favorite' THEN json_extract(body,'$.content.title') ELSE '剪贴板' END,?3) OR (json_extract(body,'$.content.type')='snippet' AND EXISTS(SELECT 1 FROM json_each(body,'$.content.tags') WHERE zapime_contains(value,?3))))";

pub(crate) fn prepare_index(tx: &Transaction<'_>) -> Result<(), Error> {
    // Auxiliary index is compatible with schema v2; old writers update it automatically.
    tx.execute_batch(&format!("CREATE INDEX IF NOT EXISTS panel_order ON records(json_extract(body,'$.deleted'),{ORDER});"))?;
    Ok(())
}
#[derive(Serialize)]
pub struct Summary {
    pub id: Uuid,
    pub revision: u64,
    #[serde(rename = "type")]
    pub kind: String,
    pub label: String,
    pub preview: String,
    pub pinned: bool,
    pub expires_at_ms: u64,
}
#[derive(Serialize)]
pub struct Page {
    pub items: Vec<Summary>,
    pub total: u64,
    pub offset: u64,
    pub next_offset: Option<u64>,
}
impl Store {
    /// No migrations, directory creation, writes or input engine. Open on a
    /// dedicated read worker after the normal profile has been initialized.
    pub fn open_panel_reader(path: &Path) -> Result<Self, Error> {
        let db = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        db.busy_timeout(Duration::from_millis(200))?;
        if db.query_row::<u32, _, _>("PRAGMA user_version", [], |r| r.get(0))? != 2 {
            return Err(Error::Version);
        }
        Ok(Self { db })
    }
    pub fn query_panel(
        &mut self,
        kind: &str,
        query: &str,
        offset: u64,
        limit: usize,
        now_ms: u64,
    ) -> Result<Page, Error> {
        if !["all", "snippet", "favorite", "clipboard"].contains(&kind)
            || query.len() > 512
            || !(1..=40).contains(&limit)
            || now_ms > i64::MAX as u64
        {
            return Err(Error::Invalid);
        }
        self.db.create_scalar_function(
            "zapime_contains",
            2,
            FunctionFlags::SQLITE_UTF8 | FunctionFlags::SQLITE_DETERMINISTIC,
            |context| {
                let text: String = context.get(0)?;
                let needle: String = context.get(1)?;
                Ok(text.to_lowercase().contains(&needle))
            },
        )?;
        let started = Instant::now();
        // Slow substring searches are interruptible, and never run on Android's
        // input worker. The bound is a query budget, not a latency benchmark.
        self.db.progress_handler(
            1000,
            Some(move || started.elapsed() > Duration::from_secs(1)),
        )?;
        let result = (|| {
            let tx = self.db.transaction()?;
            let needle = query.to_lowercase();
            let total: i64 = tx.query_row(
                &format!("SELECT count(*) FROM records WHERE {FILTER}"),
                params![kind, now_ms as i64, needle],
                |r| r.get(0),
            )?;
            let total = u64::try_from(total).map_err(|_| Error::Invalid)?;
            let start = offset.min(total);
            let sql=format!("SELECT id,revision,json_extract(body,'$.content.type'),substr({LABEL},1,40),substr(json_extract(body,'$.content.text'),1,96),COALESCE(json_extract(body,'$.content.pinned'),0),COALESCE(body -> '$.content.expires_at_ms','0') FROM records WHERE {FILTER} ORDER BY {ORDER} LIMIT ?4 OFFSET ?5");
            let mut stmt = tx.prepare(&sql)?;
            let mut items = Vec::new();
            let rows = stmt.query_map(
                params![kind, now_ms as i64, needle, limit as i64, start as i64],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, String>(2)?,
                        r.get::<_, String>(3)?,
                        r.get::<_, String>(4)?,
                        r.get::<_, bool>(5)?,
                        r.get::<_, String>(6)?,
                    ))
                },
            )?;
            for row in rows {
                let (id, revision, kind, label, preview, pinned, expires) = row?;
                items.push(Summary {
                    id: Uuid::parse_str(&id).map_err(|_| Error::Invalid)?,
                    revision: u64::try_from(revision).map_err(|_| Error::Invalid)?,
                    kind,
                    label,
                    preview,
                    pinned,
                    expires_at_ms: expires.parse().map_err(|_| Error::Invalid)?,
                });
            }
            let next = start + items.len() as u64;
            Ok(Page {
                items,
                total,
                offset: start,
                next_offset: (next < total).then_some(next),
            })
        })();
        self.db.progress_handler(0, None::<fn() -> bool>)?;
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn page_order_uses_index_without_a_temporary_sort() {
        let dir = tempfile::tempdir().unwrap();
        let mut store = Store::open(&dir.path().join("data.sqlite3")).unwrap();
        store.query_panel("all", "", 0, 20, 10).unwrap();
        let mut stmt=store.db.prepare(&format!("EXPLAIN QUERY PLAN SELECT id FROM records WHERE {FILTER} ORDER BY {ORDER} LIMIT ?4 OFFSET ?5")).unwrap();
        let plan = stmt
            .query_map(params!["all", 10, "", 20, 0], |r| r.get::<_, String>(3))
            .unwrap()
            .map(Result::unwrap)
            .collect::<Vec<_>>()
            .join("\n");
        assert!(plan.contains("panel_order"), "{plan}");
        assert!(!plan.contains("USE TEMP B-TREE"), "{plan}");
    }
}
