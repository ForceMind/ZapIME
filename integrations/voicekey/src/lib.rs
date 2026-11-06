// SPDX-License-Identifier: GPL-3.0-only
//! Isolated VoiceKey offline engine adapter for ZapIME.
//!
//! The upstream source modules below are compiled from a pinned Git submodule,
//! not copied. This build deliberately does NOT compile VoiceKey's unofficial
//! online service protocol clients, embedded service keys, or microphone code.
#[cfg(target_os = "android")]
mod android;

// These five VoiceKey modules do not depend on its cloud recognition core.
// Their author and source revision are preserved at vendor/voicekey.
#[path = "../../../vendor/voicekey/crates/wtlocal/src/fbank.rs"]
mod fbank;
#[path = "../../../vendor/voicekey/crates/wtlocal/src/kernels.rs"]
mod kernels;
#[path = "../../../vendor/voicekey/crates/wtlocal/src/model.rs"]
mod model;
#[path = "../../../vendor/voicekey/crates/wtlocal/src/stream.rs"]
mod stream;
#[path = "../../../vendor/voicekey/crates/wtlocal/src/xnet.rs"]
mod xnet;

use anyhow::{anyhow, bail, Result};
use async_trait::async_trait;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use model::Model;
use stream::Stream;

/// One minute of mono 16 kHz signed PCM, matching the existing ZapIME limit.
pub const MAX_SAMPLES: usize = 960_000;
/// Capture sends no more than 100 ms per event.
pub const MAX_FRAME_SAMPLES: usize = 1_600;
const MAX_TEXT_BYTES: usize = 65_536;
const PARTIAL_EVERY: usize = 30;

static MODEL_CACHE: Mutex<Option<(PathBuf, Arc<Model>)>> = Mutex::new(None);

/// Load one validated model for the whole process; repeated hot sessions reuse
/// its mmap instead of reloading weights on each microphone gesture.
pub(crate) fn load_model(dir: &Path) -> Result<Arc<Model>> {
    let mut cache = MODEL_CACHE.lock().map_err(|_| anyhow!("model_lock"))?;
    if let Some((old, model)) = cache.as_ref() {
        if old == dir {
            return Ok(model.clone());
        }
    }
    let model = Arc::new(Model::load(dir)?);
    *cache = Some((dir.to_path_buf(), model.clone()));
    Ok(model)
}

/// An engine receives a bounded PCM channel and emits preview-only callbacks.
#[async_trait]
pub trait RecognizerEngine: Send + Sync {
    async fn run(
        &self,
        audio: mpsc::Receiver<Vec<i16>>,
        partial: Box<dyn Fn(&str) + Send + Sync>,
    ) -> Result<String>;
    async fn prewarm(&self) {}
}

struct VoiceKeyOfflineEngine {
    dir: PathBuf,
}

#[async_trait]
impl RecognizerEngine for VoiceKeyOfflineEngine {
    async fn prewarm(&self) {
        let dir = self.dir.clone();
        let _ = tokio::task::spawn_blocking(move || load_model(&dir)).await;
    }
    async fn run(
        &self,
        mut audio: mpsc::Receiver<Vec<i16>>,
        partial: Box<dyn Fn(&str) + Send + Sync>,
    ) -> Result<String> {
        let dir = self.dir.clone();
        tokio::task::spawn_blocking(move || {
            let mut session = Stream::new(load_model(&dir)?);
            let mut last = String::new();
            let (mut previous, mut duration) = (std::time::Instant::now(), std::time::Duration::ZERO);
            while let Some(frame) = audio.blocking_recv() {
                session.push(&frame);
                while let Ok(next) = audio.try_recv() {
                    session.push(&next);
                }
                if session.fresh() >= PARTIAL_EVERY && previous.elapsed() >= duration * 3 {
                    let start = std::time::Instant::now();
                    let text = session.partial();
                    duration = start.elapsed();
                    previous = std::time::Instant::now();
                    if !text.is_empty() && text != last {
                        partial(&text);
                        last = text;
                    }
                }
            }
            Ok(session.finish())
        }).await?
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SessionId(pub u64);

#[derive(Debug, PartialEq, Eq)]
pub struct PartialResult {
    pub session: SessionId,
    pub text: String,
}

#[derive(Clone, Debug)]
pub enum RecognizerChoice {
    /// User-supplied, already validated VoiceKey-compatible local model.
    WechatOffline { model_dir: PathBuf },
}

impl RecognizerChoice {
    pub fn is_offline(&self) -> bool { true }

    pub async fn prewarm(&self) {
        let RecognizerChoice::WechatOffline { model_dir } = self;
        VoiceKeyOfflineEngine { dir: model_dir.clone() }.prewarm().await;
    }

    pub fn start(&self, id: SessionId) -> RecognitionSession {
        let RecognizerChoice::WechatOffline { model_dir } = self;
        RecognitionSession::from_engine(
            id,
            Box::new(VoiceKeyOfflineEngine { dir: model_dir.clone() }),
        )
    }
}

/// Strictly bounded ingress AND inference queue, unlike VoiceKey's original
/// unbounded Audio bus. Partial messages also have a fixed maximum count.
pub struct RecognitionSession {
    id: SessionId,
    input: Option<mpsc::Sender<Vec<i16>>>,
    task: Option<JoinHandle<Result<String>>>,
    partials: mpsc::Receiver<PartialResult>,
    accepted: usize,
    nonzero: bool,
}

impl RecognitionSession {
    /// Fake-engine injection is used only by synthetic unit tests.
    pub fn from_engine(id: SessionId, engine: Box<dyn RecognizerEngine>) -> Self {
        let (tx, rx) = mpsc::channel::<Vec<i16>>(8);
        let (partial_tx, partial_rx) = mpsc::channel::<PartialResult>(32);
        let callback: Box<dyn Fn(&str) + Send + Sync> = Box::new(move |value| {
            if value.len() <= MAX_TEXT_BYTES {
                let _ = partial_tx.try_send(PartialResult { session: id, text: value.to_owned() });
            }
        });
        let task = tokio::spawn(async move { engine.run(rx, callback).await });
        Self {
            id, input: Some(tx), task: Some(task),
            partials: partial_rx, accepted: 0, nonzero: false,
        }
    }

    pub fn session_id(&self) -> SessionId { self.id }

    pub fn push(&mut self, samples: &[i16]) -> Result<()> {
        if samples.is_empty() || samples.len() > MAX_FRAME_SAMPLES { bail!("invalid_pcm_frame"); }
        let count = self.accepted.checked_add(samples.len()).ok_or_else(|| anyhow!("audio_limit"))?;
        if count > MAX_SAMPLES { bail!("audio_limit"); }
        let sender = self.input.as_ref().ok_or_else(|| anyhow!("session_closed"))?;
        sender.try_send(samples.to_vec()).map_err(|e| match e {
            mpsc::error::TrySendError::Full(_) => anyhow!("voicekey_audio_backpressure"),
            mpsc::error::TrySendError::Closed(_) => anyhow!("voicekey_engine_stopped"),
        })?;
        self.nonzero |= samples.iter().any(|&s| s != 0);
        self.accepted = count;
        Ok(())
    }

    /// These results may be shown in a preview but MUST NOT commit into an
    /// editor until ZapIME's ticket/epoch/selection checks pass.
    pub fn poll_partial(&mut self) -> Option<PartialResult> { self.partials.try_recv().ok() }

    pub async fn finish(mut self) -> Result<String> {
        self.input.take();
        let task = self.task.take().ok_or_else(|| anyhow!("session_closed"))?;
        let text = task.await.map_err(|_| anyhow!("voicekey_task_failed"))??;
        if text.len() > MAX_TEXT_BYTES { bail!("transcript_limit"); }
        Ok(if self.nonzero { text } else { String::new() })
    }

    pub fn cancel(&mut self) {
        self.input.take();
        if let Some(task) = self.task.take() { task.abort(); }
    }
}

impl Drop for RecognitionSession {
    fn drop(&mut self) { self.cancel(); }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct FakeEngine;
    #[async_trait]
    impl RecognizerEngine for FakeEngine {
        async fn run(
            &self,
            mut audio: mpsc::Receiver<Vec<i16>>,
            callback: Box<dyn Fn(&str) + Send + Sync>,
        ) -> Result<String> {
            let mut n = 0;
            while let Some(frame) = audio.recv().await {
                n += frame.len();
                callback("合成预览");
            }
            Ok(format!("samples:{n}"))
        }
    }

    #[tokio::test]
    async fn accepts_pcm_without_cloud_runtime_or_model() {
        let mut s = RecognitionSession::from_engine(SessionId(21), Box::new(FakeEngine));
        s.push(&[1; 1_600]).unwrap();
        s.push(&[2; 800]).unwrap();
        assert_eq!(s.finish().await.unwrap(), "samples:2400");
    }

    #[tokio::test]
    async fn no_audio_and_only_zero_pcm_never_commit() {
        let empty = RecognitionSession::from_engine(SessionId(22), Box::new(FakeEngine));
        assert_eq!(empty.finish().await.unwrap(), "");
        let mut silent = RecognitionSession::from_engine(SessionId(23), Box::new(FakeEngine));
        silent.push(&[0; 1_600]).unwrap();
        assert_eq!(silent.finish().await.unwrap(), "");
    }

    #[tokio::test]
    async fn invalid_frames_and_sixty_second_cap() {
        let mut s = RecognitionSession::from_engine(SessionId(24), Box::new(FakeEngine));
        assert!(s.push(&[]).is_err());
        assert!(s.push(&[0; 1_601]).is_err());
        for _ in 0..600 {
            loop {
                match s.push(&[1; 1_600]) {
                    Ok(()) => break,
                    Err(e) if e.to_string() == "voicekey_audio_backpressure" =>
                        tokio::task::yield_now().await,
                    Err(e) => panic!("unexpected push error: {e}"),
                }
            }
        }
        assert!(s.push(&[1]).is_err());
        assert_eq!(s.finish().await.unwrap(), "samples:960000");
    }

    #[tokio::test]
    async fn cancel_rejects_new_pcm() {
        let mut s = RecognitionSession::from_engine(SessionId(25), Box::new(FakeEngine));
        s.cancel();
        assert!(s.push(&[1]).is_err());
    }

    #[test]
    fn only_offline_recognizer_is_selectable() {
        assert!(RecognizerChoice::WechatOffline { model_dir: PathBuf::from("/private/voicekey") }.is_offline());
    }
}
