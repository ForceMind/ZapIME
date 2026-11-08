// SPDX-License-Identifier: GPL-3.0-only
//! Read-only local sync evidence. This never authorizes or stages an upload.
use crate::{
    sync::{workspace_binding, workspace_budget, WorkspaceBinding},
    Error, Store,
};
use rusqlite::params;
use serde::Serialize;
use uuid::Uuid;

pub const MAX_INVENTORY_ITEMS: usize = 100;
const MAX_STORED_RECORD_BYTES: usize = 1_048_576;

#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum InventoryState {
    PendingLocalOperation,
    ServerVersionKnown,
    LocalOperationHistory,
    Untracked,
}

/// Metadata only. A known server version is not confirmation of this current
/// local snapshot, nor proof that the server still has the record. Exact local
/// snapshot flags deliberately use stored JSON bytes, not revision alone.
#[derive(Serialize)]
pub struct InventoryItem {
    pub id: Uuid,
    pub local_revision: u64,
    pub kind: u8,
    pub deleted: bool,
    pub state: InventoryState,
    pub has_pending_local_operation: bool,
    pub current_snapshot_pending: bool,
    pub has_local_operation_history: bool,
    pub current_snapshot_has_local_operation: bool,
    pub known_server_revision: Option<u64>,
}

/// Keyset navigation only, not a durable upload checkpoint or a consistent
/// multi-page snapshot. Concurrent edits/inserts may require an explicit rescan.
#[derive(Serialize)]
pub struct InventoryPage {
    pub items: Vec<InventoryItem>,
    pub next_after: Option<Uuid>,
}

fn canonical(value: Option<String>) -> Result<Uuid, Error> {
    let value = value.ok_or(Error::Invalid)?;
    let id = Uuid::parse_str(&value).map_err(|_| Error::Invalid)?;
    if id.is_nil() || id.to_string() != value {
        return Err(Error::Invalid);
    }
    Ok(id)
}

fn query(has_after: bool) -> String {
    // CASE short-circuits before json_valid/json_extract on oversized bodies.
    // SQLite returns only bounded scalars; no personal body is materialized in
    // Rust, including for the one-row lookahead.
    let body = format!(
        "CASE WHEN length(CAST(r.body AS BLOB))<={MAX_STORED_RECORD_BYTES}
         THEN CASE WHEN json_valid(r.body) THEN r.body END END"
    );
    let where_after = if has_after { "WHERE r.id>?1" } else { "" };
    format!(
        "SELECT CASE WHEN length(CAST(r.id AS BLOB))=36 THEN r.id END,r.revision,
          CASE WHEN json_type({body},'$.id')='text'
            AND length(CAST(json_extract({body},'$.id') AS BLOB))=36
            THEN json_extract({body},'$.id') END,
          CASE WHEN json_type({body},'$.revision')='integer'
            THEN json_extract({body},'$.revision') END,
          CASE WHEN json_type({body},'$.deleted') IN('true','false')
            THEN json_extract({body},'$.deleted') END,
          CASE json_extract({body},'$.content.type')
            WHEN 'dictionary' THEN 1 WHEN 'candidate_rule' THEN 2
            WHEN 'snippet' THEN 3 WHEN 'favorite' THEN 4 WHEN 'clipboard' THEN 5 END,
          EXISTS(SELECT 1 FROM operations o INDEXED BY sync_pending_record
            WHERE o.pending=1 AND json_extract(o.result,'$.id')=r.id),
          EXISTS(SELECT 1 FROM operations o INDEXED BY sync_pending_record
            WHERE o.pending=1 AND json_extract(o.result,'$.id')=r.id AND CASE WHEN length(CAST(o.result AS BLOB))<={MAX_STORED_RECORD_BYTES}
              THEN o.result=r.body ELSE 0 END),
          EXISTS(SELECT 1 FROM operations o INDEXED BY sync_pending_record
            WHERE json_extract(o.result,'$.id')=r.id),
          EXISTS(SELECT 1 FROM operations o INDEXED BY sync_pending_record
            WHERE json_extract(o.result,'$.id')=r.id AND CASE WHEN length(CAST(o.result AS BLOB))<={MAX_STORED_RECORD_BYTES}
              THEN o.result=r.body ELSE 0 END),
          (SELECT server_revision FROM sync_versions WHERE record_id=r.id)
         FROM records r {where_after} ORDER BY r.id LIMIT ?2"
    )
}

impl Store {
    /// Explicit metadata preview on a dedicated sync/read connection. Existing
    /// v2 only: does not bind, create indexes, coalesce, enqueue, encrypt, stage,
    /// acknowledge or advance a cursor. Missing legacy indexes fail closed.
    /// History scans share the existing cooperative 200ms SQLite VM budget;
    /// the item limit does not imply that total history work is O(limit).
    pub fn sync_inventory_page(
        &mut self,
        expected: &WorkspaceBinding,
        after: Option<Uuid>,
        limit: usize,
    ) -> Result<InventoryPage, Error> {
        if expected.profile_id.is_nil()
            || expected.key_id.is_nil()
            || after.is_some_and(|id| id.is_nil())
            || !(1..=MAX_INVENTORY_ITEMS).contains(&limit)
        {
            return Err(Error::Invalid);
        }
        workspace_budget(&mut self.db, |db| {
            let tx = db.transaction()?;
            if workspace_binding(&tx)?.as_ref() != Some(expected) {
                return Err(Error::SyncWorkspace);
            }
            // Bad operation metadata cannot disappear from a pending=0/1-only
            // filter and turn a restored row into an apparent untracked row.
            // This is an index scan, so large histories may hit the VM budget.
            let invalid: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM operations INDEXED BY sync_pending_record
                 WHERE typeof(pending)<>'integer' OR pending NOT IN(0,1)
                   OR typeof(json_extract(result,'$.id'))<>'text'
                   OR length(json_extract(result,'$.id'))<>36
                   OR substr(json_extract(result,'$.id'),9,1)<>'-'
                   OR substr(json_extract(result,'$.id'),14,1)<>'-'
                   OR substr(json_extract(result,'$.id'),19,1)<>'-'
                   OR substr(json_extract(result,'$.id'),24,1)<>'-'
                   OR length(replace(json_extract(result,'$.id'),'-',''))<>32
                   OR replace(json_extract(result,'$.id'),'-','') GLOB '*[^0-9a-f]*'
                   OR json_extract(result,'$.id')='00000000-0000-0000-0000-000000000000')",
                [],
                |row| row.get(0),
            )?;
            if invalid {
                return Err(Error::SyncHistory);
            }
            let sql = query(after.is_some());
            let mut statement = tx.prepare(&sql)?;
            let after = after.map(|id| id.to_string());
            let mut rows = statement.query(params![after, (limit + 1) as i64])?;
            let mut items = Vec::with_capacity(limit);
            let mut has_more = false;
            while let Some(row) = rows.next()? {
                let id = canonical(row.get(0)?)?;
                let revision: i64 = row.get(1)?;
                let body_id = canonical(row.get(2)?)?;
                let body_revision: Option<i64> = row.get(3)?;
                let deleted: Option<bool> = row.get(4)?;
                let kind: Option<u8> = row.get(5)?;
                if revision <= 0 || body_id != id || body_revision != Some(revision) {
                    return Err(Error::Invalid);
                }
                let deleted = deleted.ok_or(Error::Invalid)?;
                let kind = kind.ok_or(Error::Invalid)?;
                let pending: bool = row.get(6)?;
                let current_pending: bool = row.get(7)?;
                let history: bool = row.get(8)?;
                let current_history: bool = row.get(9)?;
                let known: Option<i64> = row.get(10)?;
                let known = known
                    .map(|value| u64::try_from(value).map_err(|_| Error::SyncHistory))
                    .transpose()?;
                if known == Some(0)
                    || (current_pending && !pending)
                    || (current_history && !history)
                {
                    return Err(Error::SyncHistory);
                }
                if items.len() == limit {
                    has_more = true;
                    break;
                }
                let state = if pending {
                    InventoryState::PendingLocalOperation
                } else if known.is_some() {
                    InventoryState::ServerVersionKnown
                } else if history {
                    InventoryState::LocalOperationHistory
                } else {
                    InventoryState::Untracked
                };
                items.push(InventoryItem {
                    id,
                    local_revision: revision as u64,
                    kind,
                    deleted,
                    state,
                    has_pending_local_operation: pending,
                    current_snapshot_pending: current_pending,
                    has_local_operation_history: history,
                    current_snapshot_has_local_operation: current_history,
                    known_server_revision: known,
                });
            }
            let next_after = if has_more {
                items.last().map(|item| item.id)
            } else {
                None
            };
            Ok(InventoryPage { items, next_after })
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keyset_and_existing_history_indexes_are_visible_in_actual_query_plan() {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(&dir.path().join("query-plan.db")).unwrap();
        let mut statement = store
            .db
            .prepare(&format!("EXPLAIN QUERY PLAN {}", query(true)))
            .unwrap();
        let plan = statement
            .query_map(params![Uuid::new_v4().to_string(), 11], |row| {
                row.get::<_, String>(3)
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert!(
            plan.iter()
                .any(|line| line.contains("SEARCH r USING INDEX sqlite_autoindex_records_1")),
            "{plan:?}"
        );
        assert!(
            plan.iter()
                .any(|line| line.contains("SEARCH o USING INDEX sync_pending_record")),
            "{plan:?}"
        );
        assert!(
            plan.iter()
                .any(|line| line.contains("SCAN o USING COVERING INDEX sync_pending_record")),
            "{plan:?}"
        );
    }
}
