// SPDX-License-Identifier: GPL-3.0-only
//! ZapIME 的离线会话适配层。解码与候选生成由固定版本的 MSIME 引擎负责。
use msime_engine::{Command, KeyResult, SchemeSet, SchemeType, Session, SessionOptions};
use serde_json::{json, Value};
use std::path::Path;

#[cfg(target_os = "android")]
mod android;

pub struct InputSession {
    engine: Session,
    generation: u64,
    private: bool,
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
        Ok(Self {
            engine,
            generation: 0,
            private,
        })
    }

    /// 单线程串行调用；候选选择必须携带显示时的代次，拒绝过期点击。
    pub fn request(&mut self, request: &Value) -> Value {
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
                self.engine.command(command)
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
                if index >= self.engine.snapshot().candidates.len() {
                    return json!({"error":"invalid_candidate"});
                }
                self.engine.select(index)
            }
            "reset" => self.engine.command(Command::Cancel),
            "view" => return self.view(KeyResult::handled()),
            _ => return json!({"error":"unknown_operation"}),
        };
        self.generation += 1;
        self.view(result)
    }

    fn view(&self, result: KeyResult) -> Value {
        let snapshot = self.engine.snapshot();
        json!({
            "generation": self.generation,
            "handled": result.handled,
            "commit": result.commit,
            "preedit": snapshot.preedit,
            "candidates": snapshot.candidates.iter().take(40).map(|w| w.word.clone()).collect::<Vec<_>>(),
            "private": self.private
        })
    }
}
