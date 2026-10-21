// SPDX-License-Identifier: GPL-3.0-only
//! ZapIME 的离线会话适配层。解码与候选生成由固定版本的 MSIME 引擎负责。
use msime_engine::{Command, KeyResult, SchemeSet, SchemeType, Session, SessionOptions};
use serde_json::{json, Value};
use std::path::Path;
use uuid::Uuid;
use zapime_data::{Content, Record, Store};

#[cfg(target_os = "android")]
mod android;
pub mod ffi;

pub struct InputSession {
    engine: Session,
    generation: u64,
    private: bool,
    store: Option<Store>,
    personal: Vec<Record>,
}

impl InputSession {
    /// 调用方在后台准备资源后创建；返回后按键处理无网络与资源下载。
    pub fn open(
        resources: &Path,
        data: &Path,
        cache: &Path,
        private: bool,
    ) -> Result<Self, String> {
        let paths = msime_engine::prepare_runtime_paths(resources, data, cache, "zapime-v1")
            .map_err(|_| "resource_preparation_failed")?;
        let mut options = SessionOptions::new(paths);
        options.enabled_schemes = SchemeSet::of(&[SchemeType::Quanpin]);
        options.helpcode = false;
        options.learning = !private;
        options.personal_context = !private;
        let mut engine = Session::new(options).map_err(|_| "session_open_failed")?;
        engine.set_cloud_candidates_enabled(false);
        let store = if private {
            None
        } else {
            let mut store =
                Store::open(&data.join("personal.sqlite3")).map_err(|_| "personal_store_failed")?;
            let device = store.device_id().map_err(|_| "personal_store_failed")?;
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis().min(u64::MAX as u128) as u64)
                .unwrap_or(0);
            store
                .expire_clipboard(now, device)
                .map_err(|_| "personal_store_failed")?;
            Some(store)
        };
        let personal = match &store {
            Some(store) => store.records(false).map_err(|_| "personal_store_failed")?,
            None => Vec::new(),
        };
        Ok(Self {
            store,
            personal,
            engine,
            generation: 0,
            private,
        })
    }

    /// 单线程串行调用；候选选择必须携带显示时的代次，拒绝过期点击。
    pub fn request(&mut self, request: &Value) -> Value {
        if request["op"] == "personal_pin" {
            if self.private {
                return json!({"error":"private_session"});
            }
            if request["generation"].as_u64() != Some(self.generation) {
                return json!({"error":"stale_generation"});
            }
            let Some(index) = request["index"]
                .as_u64()
                .and_then(|n| usize::try_from(n).ok())
            else {
                return json!({"error":"invalid_candidate"});
            };
            let Some((text, _)) = self.display_candidates().get(index).cloned() else {
                return json!({"error":"invalid_candidate"});
            };
            let code = self.engine.snapshot().editing_text;
            if code.is_empty() {
                return json!({"error":"invalid_record"});
            }
            let existing=self.personal.iter().find(|record| matches!(&record.content,Content::CandidateRule{scheme,code:old_code,position,..} if scheme=="pinyin" && old_code==&code && *position==1));
            let (id, revision) = existing
                .map(|r| (r.id, r.revision))
                .unwrap_or((Uuid::new_v4(), 0));
            return self.request(&json!({"op":"personal_edit","id":id,"operation_id":Uuid::new_v4(),"expected_revision":revision,"content":{"type":"candidate_rule","scheme":"pinyin","code":code,"text":text,"position":1}}));
        }
        let result = match request["op"].as_str().unwrap_or("") {
            "key" => {
                let Some(text) = request["text"]
                    .as_str()
                    .filter(|s| s.len() == 1 && s.is_ascii())
                else {
                    return json!({"error":"invalid_key"});
                };
                let byte = text.as_bytes()[0];
                if byte.is_ascii_alphabetic() || byte == b'\'' {
                    self.engine.character(byte, false)
                } else {
                    self.engine.punctuation(byte)
                }
            }
            "command" => {
                let Some(command) = request["name"].as_str().and_then(Command::from_name) else {
                    return json!({"error":"invalid_command"});
                };
                if command == Command::CommitCandidate && !self.display_candidates().is_empty() {
                    self.select_display(0)
                } else {
                    self.engine.command(command)
                }
            }
            "select" => {
                if request["generation"].as_u64() != Some(self.generation) {
                    return json!({"error":"stale_generation"});
                }
                let Some(index) = request["index"]
                    .as_u64()
                    .and_then(|n| usize::try_from(n).ok())
                else {
                    return json!({"error":"invalid_candidate"});
                };
                if index >= self.display_candidates().len() {
                    return json!({"error":"invalid_candidate"});
                }
                self.select_display(index)
            }
            "personal_edit" => {
                let Some(store) = self.store.as_mut() else {
                    return json!({"error":"private_session"});
                };
                let id = request["id"].as_str().and_then(|s| Uuid::parse_str(s).ok());
                let operation = request["operation_id"]
                    .as_str()
                    .and_then(|s| Uuid::parse_str(s).ok());
                let revision = request["expected_revision"].as_u64();
                let content = serde_json::from_value::<Content>(request["content"].clone());
                let (Some(id), Some(operation), Some(revision), Ok(content)) =
                    (id, operation, revision, content)
                else {
                    return json!({"error":"invalid_record"});
                };
                let Ok(device) = store.device_id() else {
                    return json!({"error":"personal_store_failed"});
                };
                if let Err(error) = store.edit(
                    operation,
                    id,
                    revision,
                    device,
                    content,
                    request["deleted"].as_bool().unwrap_or(false),
                ) {
                    return json!({"error": error.to_string()});
                }
                let Ok(personal) = store.records(false) else {
                    return json!({"error":"personal_store_failed"});
                };
                self.personal = personal;
                KeyResult::handled()
            }
            "personal_export" => {
                let Some(store) = &self.store else {
                    return json!({"error":"private_session"});
                };
                return match store.export_plaintext() {
                    Ok(bytes) => json!({"archive":String::from_utf8_lossy(&bytes)}),
                    Err(error) => json!({"error":error.to_string()}),
                };
            }
            "personal_import" => {
                let Some(store) = self.store.as_mut() else {
                    return json!({"error":"private_session"});
                };
                let Some(archive) = request["archive"].as_str() else {
                    return json!({"error":"invalid_archive"});
                };
                if let Err(error) = store.restore_plaintext(archive.as_bytes()) {
                    return json!({"error":error.to_string()});
                }
                let Ok(personal) = store.records(false) else {
                    return json!({"error":"personal_store_failed"});
                };
                self.personal = personal;
                KeyResult::handled()
            }
            "personal_list" => return json!({"records":self.personal}),
            "reset" => self.engine.command(Command::Cancel),
            "view" => return self.view(KeyResult::handled()),
            _ => return json!({"error":"unknown_operation"}),
        };
        self.generation += 1;
        self.view(result)
    }

    // 自定义条目只在整段编码精确匹配时覆盖；其余拼音解码继续走原引擎。
    fn display_candidates(&self) -> Vec<(String, Option<usize>)> {
        let snapshot = self.engine.snapshot();
        let mut rows: Vec<_> = snapshot
            .candidates
            .iter()
            .enumerate()
            .map(|(i, w)| (w.word.clone(), Some(i)))
            .collect();
        if snapshot.editing_text.is_empty() {
            rows.truncate(40);
            return rows;
        }
        let matches =
            |code: &str| code == snapshot.editing_text || code == snapshot.normalized_segmentation;
        let mut additions = Vec::new();
        let mut rules = Vec::new();
        for record in &self.personal {
            match &record.content {
                Content::Dictionary {
                    scheme,
                    code,
                    text,
                    weight,
                } if scheme == "pinyin" && matches(code) => additions.push((text.clone(), *weight)),
                Content::Snippet { code, text, .. } if matches(code) => {
                    additions.push((text.clone(), i64::MAX))
                }
                Content::CandidateRule {
                    scheme,
                    code,
                    text,
                    position,
                } if scheme == "pinyin" && matches(code) => rules.push((text.clone(), *position)),
                _ => {}
            }
        }
        additions.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
        for (text, _) in additions.into_iter().rev() {
            rows.retain(|row| row.0 != text);
            rows.insert(0, (text, None));
        }
        rules.sort_by(|a, b| a.1.cmp(&b.1).then(a.0.cmp(&b.0)));
        for (text, position) in rules {
            let existing = rows
                .iter()
                .find(|row| row.0 == text)
                .cloned()
                .unwrap_or((text.clone(), None));
            rows.retain(|row| row.0 != text);
            let index = usize::from(position - 1).min(rows.len());
            rows.insert(index, existing);
        }
        rows.truncate(40);
        rows
    }
    fn select_display(&mut self, index: usize) -> KeyResult {
        match self.display_candidates().get(index).cloned() {
            Some((_, Some(engine_index))) => self.engine.select(engine_index),
            Some((text, None)) => {
                self.engine.command(Command::Cancel);
                KeyResult::committed(text)
            }
            None => KeyResult::unhandled(),
        }
    }

    fn view(&self, result: KeyResult) -> Value {
        let snapshot = self.engine.snapshot();
        json!({
            "generation": self.generation,
            "handled": result.handled,
            "commit": result.commit,
            "preedit": snapshot.preedit,
            "candidates": self.display_candidates().into_iter().map(|row|row.0).collect::<Vec<_>>(),
            "code": snapshot.editing_text,
            "private": self.private
        })
    }
}
