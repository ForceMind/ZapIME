// SPDX-License-Identifier: GPL-3.0-only
//! Local panel reads are independent of input generations and never return full bodies.
use super::*;

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis().min(u64::MAX as u128) as u64)
        .unwrap_or(0)
}
fn active(record: &Record, now: u64) -> bool {
    !record.deleted
        && !matches!(record.content, Content::Clipboard { expires_at_ms, .. } if expires_at_ms <= now)
}
fn fields(content: &Content) -> Option<(&str, &str, &str, bool, u64)> {
    match content {
        Content::Snippet { code, text, .. } => Some(("snippet", code, text, false, 0)),
        Content::Favorite { title, text } => Some(("favorite", title, text, false, 0)),
        Content::Clipboard {
            text,
            pinned,
            expires_at_ms,
        } => Some(("clipboard", "剪贴板", text, *pinned, *expires_at_ms)),
        _ => None,
    }
}
impl InputSession {
    pub(super) fn query_personal(&mut self, request: &Value) -> Value {
        let Some(store) = &self.store else {
            return json!({"error":"private_session"});
        };
        let kind = request["kind"].as_str().unwrap_or("all");
        let query = request["query"].as_str().unwrap_or("");
        let limit = request["limit"].as_u64().unwrap_or(20);
        let offset = request["offset"].as_u64().unwrap_or(0);
        if !["all", "snippet", "favorite", "clipboard"].contains(&kind)
            || query.len() > 512
            || !(1..=40).contains(&limit)
            || request.get("query").is_some_and(|v| !v.is_string())
            || request.get("offset").is_some_and(|v| !v.is_u64())
            || request.get("limit").is_some_and(|v| !v.is_u64())
            || request.get("kind").is_some_and(|v| !v.is_string())
        {
            return json!({"error":"invalid_query"});
        }
        let Ok(records) = store.records(false) else {
            return json!({"error":"personal_store_failed"});
        };
        let now = now_ms();
        let needle = query.to_lowercase();
        let mut rows: Vec<_> = records.iter().filter(|r| active(r, now)).filter_map(|r| {
            let (record_kind, label, text, pinned, expires) = fields(&r.content)?;
            let tags_match = matches!(&r.content, Content::Snippet { tags, .. } if tags.iter().any(|t|t.to_lowercase().contains(&needle)));
            if (kind != "all" && kind != record_kind) || !(text.to_lowercase().contains(&needle) || label.to_lowercase().contains(&needle) || tags_match) { return None; }
            Some((r, record_kind, label, text, pinned, expires))
        }).collect();
        rows.sort_by(|a, b| {
            b.4.cmp(&a.4)
                .then_with(|| a.1.cmp(b.1))
                .then_with(|| b.5.cmp(&a.5))
                .then_with(|| a.2.cmp(b.2))
                .then_with(|| a.0.id.cmp(&b.0.id))
        });
        let total = rows.len();
        let start = usize::try_from(offset).unwrap_or(usize::MAX).min(total);
        let page: Vec<_> = rows.iter().skip(start).take(limit as usize).map(|(r, kind, label, text, pinned, expires)| json!({"id":r.id,"revision":r.revision,"type":kind,"label":label.chars().take(40).collect::<String>(),"preview":text.chars().take(96).collect::<String>(),"pinned":pinned,"expires_at_ms":expires})).collect();
        let next = start + page.len();
        json!({"items":page,"total":total,"offset":start,"next_offset":if next<total {Some(next)} else {None}})
    }
    fn panel_record(&self, request: &Value) -> Result<Record, &'static str> {
        let store = self.store.as_ref().ok_or("private_session")?;
        let id = request["id"]
            .as_str()
            .and_then(|s| Uuid::parse_str(s).ok())
            .ok_or("invalid_record")?;
        let revision = request["expected_revision"]
            .as_u64()
            .ok_or("invalid_record")?;
        let record = store
            .get(id)
            .map_err(|_| "personal_store_failed")?
            .filter(|r| active(r, now_ms()))
            .ok_or("record_unavailable")?;
        if record.revision != revision {
            return Err("revision_conflict");
        }
        if fields(&record.content).is_none() {
            return Err("unsupported_record");
        }
        Ok(record)
    }
    pub(super) fn commit_personal(&mut self, request: &Value) -> Result<KeyResult, &'static str> {
        let record = self.panel_record(request)?;
        if !self.engine.snapshot().preedit.is_empty() {
            return Err("composition_active");
        }
        let (_, _, text, _, _) = fields(&record.content).ok_or("unsupported_record")?;
        Ok(KeyResult::committed(text.to_string()))
    }
    pub(super) fn act_personal(&mut self, request: &Value) -> Result<KeyResult, String> {
        let record = self.panel_record(request).map_err(String::from)?;
        let operation = request["operation_id"]
            .as_str()
            .and_then(|s| Uuid::parse_str(s).ok())
            .ok_or("invalid_record")?;
        let action = request["action"].as_str().ok_or("invalid_action")?;
        let mut content = record.content.clone();
        let (id, revision, deleted) = match action {
            "delete" => (record.id, record.revision, true),
            "pin" | "unpin" => {
                let Content::Clipboard { pinned, .. } = &mut content else {
                    return Err("unsupported_record".into());
                };
                *pinned = action == "pin";
                (record.id, record.revision, false)
            }
            "favorite" => {
                let id = request["destination_id"]
                    .as_str()
                    .and_then(|s| Uuid::parse_str(s).ok())
                    .ok_or("invalid_record")?;
                let (_, label, text, _, _) = fields(&record.content).ok_or("unsupported_record")?;
                content = Content::Favorite {
                    title: if label == "剪贴板" {
                        text.chars().take(30).collect()
                    } else {
                        label.to_string()
                    },
                    text: text.to_string(),
                };
                (id, 0, false)
            }
            _ => return Err("invalid_action".into()),
        };
        let store = self.store.as_mut().ok_or("private_session")?;
        let device = store.device_id().map_err(|e| e.to_string())?;
        store
            .edit(operation, id, revision, device, content, deleted)
            .map_err(|e| e.to_string())?;
        self.personal = store.records(false).map_err(|e| e.to_string())?;
        Ok(KeyResult::handled())
    }
}
