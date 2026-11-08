// SPDX-License-Identifier: GPL-3.0-only
//! 本地神经重排的宿主接缝；只接受同一快照中完整候选的排列，不接收新文本。
use crate::{CommitSource, InputSession};
use msime_engine::{CandidateSource, KeyResult, LocalInputMode, SchemeType, SessionSnapshot};
use serde_json::{json, Value};
use zapime_data::Content;

pub(crate) const MODEL_ID: &str = "msime-keystroke-8m-rerank-v2";
const MAX_CANDIDATES: usize = 32;
const MAX_CHARACTERS: usize = 64;

pub(crate) struct CandidateRanking {
    enabled: bool,
    order: Option<Vec<usize>>,
}

pub(crate) fn row_id(display_index: usize, engine_index: Option<usize>) -> String {
    match engine_index {
        Some(index) => format!("engine:{index}"),
        None => format!("saved:{display_index}"),
    }
}

impl CandidateRanking {
    pub(crate) fn new(enabled: bool) -> Self {
        Self {
            enabled,
            order: None,
        }
    }

    pub(crate) fn applied(&self) -> bool {
        self.order.is_some()
    }

    pub(crate) fn invalidate(&mut self) {
        self.order = None;
    }

    fn eligible(&self, snapshot: &SessionSnapshot) -> Vec<usize> {
        if !self.enabled
            || snapshot.editing_text.is_empty()
            || snapshot.scheme != SchemeType::Quanpin
            || snapshot.local_mode != LocalInputMode::None
            || snapshot.dedicated_english
            || !snapshot.conversion.is_empty()
        {
            return Vec::new();
        }
        snapshot
            .candidates
            .iter()
            .enumerate()
            .take(40)
            .filter(|(index, candidate)| {
                // 是否消费完整原始输入由引擎给出，不从字数或拼音长度重新猜测。
                snapshot.candidate_answers_key.get(*index) == Some(&true)
                    && candidate.fixed_position == 0
                    && matches!(
                        candidate.source,
                        CandidateSource::Database
                            | CandidateSource::Generated
                            | CandidateSource::NeuralKeyboard
                    )
                    && !candidate.word.is_empty()
                    && candidate.word.chars().count() <= MAX_CHARACTERS
                    && candidate.word.chars().all(is_han)
            })
            .map(|(index, _)| index)
            .collect()
    }

    pub(crate) fn view(&self, snapshot: &SessionSnapshot, eligible: &[usize]) -> Value {
        let candidates: Vec<_> = eligible
            .iter()
            .map(|&index| {
                json!({"id": row_id(0, Some(index)), "text": snapshot.candidates[index].word})
            })
            .collect();
        json!({
            "enabled": self.enabled,
            "applied": self.applied(),
            "model": MODEL_ID,
            "candidates": candidates,
        })
    }

    pub(crate) fn reorder(&self, rows: &mut [(String, Option<usize>)]) {
        let Some(order) = &self.order else { return };
        let slots: Vec<_> = rows
            .iter()
            .enumerate()
            .filter_map(|(slot, row)| {
                row.1
                    .is_some_and(|index| order.contains(&index))
                    .then_some(slot)
            })
            .collect();
        let selected: Option<Vec<_>> = order
            .iter()
            .map(|&index| rows.iter().find(|row| row.1 == Some(index)).cloned())
            .collect();
        let Some(selected) = selected.filter(|selected| selected.len() == slots.len()) else {
            return;
        };
        // 只重排最终可见列表中的既有槽；个人/部分项与分页边界都不变。
        for (slot, row) in slots.into_iter().zip(selected) {
            rows[slot] = row;
        }
    }
}

fn is_han(c: char) -> bool {
    matches!(c, '\u{3400}'..='\u{4dbf}' | '\u{4e00}'..='\u{9fff}' | '\u{f900}'..='\u{faff}' | '\u{20000}'..='\u{323af}')
}

impl InputSession {
    fn exact_ranking_reading(&self, snapshot: &SessionSnapshot, index: usize) -> bool {
        let candidate = &snapshot.candidates[index];
        // answers_key describes composition consumption. Dictionary prefix
        // completions can still consume the whole input, so require the actual
        // full dictionary reading as well. No Han-character-count heuristic.
        if candidate.canonical_pinyin.is_empty()
            || candidate.fuzzy
            || !candidate.corrected_from.is_empty()
        {
            return false;
        }
        if self.engine.grid_composing() {
            crate::nine_key_matches(&candidate.canonical_pinyin, &snapshot.preedit)
        } else {
            candidate
                .canonical_pinyin
                .bytes()
                .filter(|&byte| byte != b'\'')
                .map(|byte| byte.to_ascii_lowercase())
                .eq(snapshot
                    .editing_text
                    .bytes()
                    .filter(|&byte| byte != b'\'')
                    .map(|byte| byte.to_ascii_lowercase()))
        }
    }

    pub(crate) fn rankable_indices(&self, snapshot: &SessionSnapshot) -> Vec<usize> {
        let eligible = self.ranking.eligible(snapshot);
        let matches = |code: &str| {
            if self.engine.grid_composing() {
                crate::nine_key_matches(code, &snapshot.preedit)
            } else {
                code == snapshot.editing_text || code == snapshot.normalized_segmentation
            }
        };
        let protected: Vec<_> = self
            .personal
            .iter()
            .filter_map(|record| match &record.content {
                Content::CandidateRule {
                    scheme, code, text, ..
                } if scheme == "pinyin" && matches(code) => Some(text),
                _ => None,
            })
            .collect();
        self.base_display_candidates()
            .iter()
            .filter_map(|(text, index)| {
                index.filter(|index| {
                    eligible.contains(index)
                        && !protected.contains(&text)
                        && self.exact_ranking_reading(snapshot, *index)
                })
            })
            .take(MAX_CANDIDATES)
            .collect()
    }

    pub(crate) fn configure_ranking(&mut self, request: &Value) -> Value {
        let Some(enabled) = request["enabled"].as_bool() else {
            return json!({"error":"invalid_ranking_setting"});
        };
        let enabled = enabled && !self.private;
        if self.ranking.enabled != enabled {
            self.ranking.enabled = enabled;
            self.ranking.invalidate();
            self.generation += 1;
        }
        self.view(KeyResult::handled(), CommitSource::Engine)
    }

    pub(crate) fn apply_ranking(&mut self, request: &Value) -> Value {
        if request["generation"].as_u64() != Some(self.generation) {
            return json!({"error":"stale_generation"});
        }
        if !self.ranking.enabled || self.private {
            return json!({"error":"candidate_ranking_disabled"});
        }
        if self.ranking.applied() {
            return json!({"error":"ranking_already_applied"});
        }
        if request["model"].as_str() != Some(MODEL_ID) {
            return json!({"error":"unsupported_ranking_model"});
        }
        let snapshot = self.engine.snapshot();
        let eligible = self.rankable_indices(&snapshot);
        let Some(ids) = request["ids"]
            .as_array()
            .filter(|ids| ids.len() == eligible.len())
        else {
            return json!({"error":"invalid_ranking_candidates"});
        };
        if eligible.len() < 2 {
            return json!({"error":"insufficient_ranking_candidates"});
        }
        let mut remaining = eligible.clone();
        let mut order = Vec::with_capacity(ids.len());
        for id in ids {
            let Some(position) = remaining
                .iter()
                .position(|&index| id.as_str() == Some(row_id(0, Some(index)).as_str()))
            else {
                return json!({"error":"invalid_ranking_candidates"});
            };
            order.push(remaining.remove(position));
        }
        let changed = order != eligible;
        self.ranking.order = Some(order);
        if changed {
            self.generation += 1;
        }
        self.view(KeyResult::handled(), CommitSource::Engine)
    }

    pub(crate) fn display_answers_key(&self, index: usize) -> bool {
        let snapshot = self.engine.snapshot();
        match self.display_candidates().get(index) {
            Some((_, Some(index))) => snapshot.candidate_answers_key.get(*index) == Some(&true),
            // 个人整码快捷项沿用现有直接提交语义。
            Some((_, None)) => true,
            None => false,
        }
    }

    pub(crate) fn visible_candidate_index(&self, request: &Value) -> Option<usize> {
        if request["composition_revision"].as_u64() != Some(self.composition_revision) {
            return None;
        }
        let id = request["first_candidate_id"].as_str()?;
        self.display_candidates()
            .iter()
            .enumerate()
            .position(|(index, row)| row_id(index, row.1) == id)
    }

    pub(crate) fn resolve_selection(&self, request: &Value) -> Result<usize, &'static str> {
        let rows = self.display_candidates();
        if let Some(revision) = request.get("composition_revision") {
            if revision.as_u64() != Some(self.composition_revision) {
                return Err("stale_generation");
            }
            let id = request["id"].as_str().ok_or("invalid_candidate")?;
            // 纯重排只更新展示代次；同一输入代里的点击仍按原候选身份提交。
            return rows
                .iter()
                .enumerate()
                .position(|(index, row)| row_id(index, row.1) == id)
                .ok_or("stale_candidate_id");
        }
        if request["generation"].as_u64() != Some(self.generation) {
            return Err("stale_generation");
        }
        let index = request["index"]
            .as_u64()
            .and_then(|n| usize::try_from(n).ok())
            .filter(|&index| index < rows.len())
            .ok_or("invalid_candidate")?;
        if let Some(id) = request.get("id") {
            if id.as_str() != Some(row_id(index, rows[index].1).as_str()) {
                return Err("stale_candidate_id");
            }
        }
        Ok(index)
    }
}
