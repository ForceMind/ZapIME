// SPDX-License-Identifier: GPL-3.0-only
//! Optional VoiceKey recognizer adapter. No keyboard access, audio capture or networking.
//! Runs only when invoked by the separate ZapIME voice worker.
//! The default/production ZapIME recognition path remains unchanged.
use anyhow::{anyhow, bail, Result};
use std::path::PathBuf;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use voicekey_core::{Audio, Engine, Partial};
use voicekey_wtlocal::LocalEngine;

/// One minute of mono 16 kHz signed PCM, matching the existing ZapIME limit.
pub const MAX_SAMPLES: usize = 960_000;
/// ZapIME's capture worker submits at most 100 ms at a time.
pub const MAX_FRAME_SAMPLES: usize = 1_600;
const MAX_TEXT_BYTES: usize = 65_536;

/// Identification-only. The input method's own epoch/selection transaction is
/// the authority for deciding whether a result may be inserted.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SessionId(pub u64);

#[derive(Debug, PartialEq, Eq)]
pub struct PartialResult {
    pub session: SessionId,
    pub text: String,
}

#[derive(Clone, Debug)]
pub enum RecognizerChoice {
    /// User-supplied, already extracted and validated VoiceKey-compatible model.
    /// This adapter NEVER downloads or redistributes third-party model assets.
    WechatOffline { model_dir: PathBuf },
}

impl RecognizerChoice {
    pub fn is_offline(&self) -> bool {
        true
    }

    /// Prepare heavy model state on a non-UI runtime before recording starts.
    pub async fn prewarm(&self) {
        let RecognizerChoice::WechatOffline { model_dir } = self;
        LocalEngine { dir: model_dir.clone() }.prewarm().await;
    }

    pub fn start(&self, session: SessionId) -> RecognitionSession {
        let RecognizerChoice::WechatOffline { model_dir } = self;
        RecognitionSession::from_engine(
            session,
            Box::new(LocalEngine { dir: model_dir.clone() }),
        )
    }
}

/// A narrow boundary around VoiceKey's unbounded Audio API. The *input*
/// channel is bounded and sessions enforce a total 60-second sample cap.
/// VoiceKey receives its existing channel type only inside this adapter.
pub struct RecognitionSession {
    id: SessionId,
    input: Option<mpsc::Sender<Vec<i16>>>,
    forwarder: Option<JoinHandle<()>>,
    recognizer: Option<JoinHandle<Result<String>>>,
    partials: mpsc::Receiver<PartialResult>,
    accepted: usize,
    has_nonzero_pcm: bool,
}

impl RecognitionSession {
    /// Internal engine hook is useful for synthetic tests. Do not pass cloud
    /// engines here from product code; ZapIME has no cloud consent flow yet.
    pub fn from_engine(id: SessionId, engine: Box<dyn Engine>) -> Self {
        let (input_tx, mut input_rx) = mpsc::channel::<Vec<i16>>(8);
        let (audio_tx, audio_rx): (mpsc::UnboundedSender<Vec<i16>>, Audio) =
            mpsc::unbounded_channel();
        let forwarder = tokio::spawn(async move {
            while let Some(frame) = input_rx.recv().await {
                if audio_tx.send(frame).is_err() {
                    break;
                }
            }
            // Dropping audio_tx signals end of recording to VoiceKey.
        });

        let (partial_tx, partial_rx) = mpsc::channel::<PartialResult>(32);
        let callback: Partial = Box::new(move |value: &str| {
            // Never let a slow UI cause unbounded event buffering.
            if value.len() <= MAX_TEXT_BYTES {
                let _ = partial_tx.try_send(PartialResult {
                    session: id,
                    text: value.to_owned(),
                });
            }
        });
        let recognizer = tokio::spawn(async move { engine.run(audio_rx, callback).await });
        Self {
            id,
            input: Some(input_tx),
            forwarder: Some(forwarder),
            recognizer: Some(recognizer),
            partials: partial_rx,
            accepted: 0,
            has_nonzero_pcm: false,
        }
    }

    pub fn session_id(&self) -> SessionId {
        self.id
    }

    /// Nonblocking for keyboard responsiveness: a full queue is an error,
    /// never a reason to stall the IME input thread or discard audio silently.
    pub fn push(&mut self, samples: &[i16]) -> Result<()> {
        if samples.is_empty() || samples.len() > MAX_FRAME_SAMPLES {
            bail!("invalid_pcm_frame");
        }
        let next = self.accepted.checked_add(samples.len()).ok_or_else(|| anyhow!("audio_limit"))?;
        if next > MAX_SAMPLES {
            bail!("audio_limit");
        }
        let sender = self.input.as_ref().ok_or_else(|| anyhow!("session_closed"))?;
        sender.try_send(samples.to_vec()).map_err(|e| match e {
            mpsc::error::TrySendError::Full(_) => anyhow!("voicekey_audio_backpressure"),
            mpsc::error::TrySendError::Closed(_) => anyhow!("voicekey_engine_stopped"),
        })?;
        self.accepted = next;
        self.has_nonzero_pcm |= samples.iter().any(|&n| n != 0);
        Ok(())
    }

    /// UI must treat these as preview-only; never insert them directly.
    pub fn poll_partial(&mut self) -> Option<PartialResult> {
        self.partials.try_recv().ok()
    }

    /// Drains the accepted frames, lets VoiceKey finalize, and returns text to
    /// ZapIME's existing "confirm insert once" / focus-epoch guard.
    pub async fn finish(mut self) -> Result<String> {
        self.input.take();
        if let Some(forwarder) = self.forwarder.take() {
            forwarder.await.map_err(|_| anyhow!("voicekey_forwarder_failed"))?;
        }
        let task = self.recognizer.take().ok_or_else(|| anyhow!("session_closed"))?;
        let text = task.await.map_err(|_| anyhow!("voicekey_task_failed"))??;
        if text.len() > MAX_TEXT_BYTES {
            bail!("transcript_limit");
        }
        // A silent/no-input session must not display model hallucinations.
        Ok(if self.has_nonzero_pcm { text } else { String::new() })
    }

    /// Abort the session when the input editor, caret, or privacy state changes.
    /// VoiceKey's already-running blocking inference may finish its current
    /// step; no late event is allowed to mutate the host's editor.
    pub fn cancel(&mut self) {
        self.input.take();
        if let Some(task) = self.forwarder.take() {
            task.abort();
        }
        if let Some(task) = self.recognizer.take() {
            task.abort();
        }
    }
}

impl Drop for RecognitionSession {
    fn drop(&mut self) {
        self.cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::Result;
    use async_trait::async_trait;

    struct FakeEngine;
    #[async_trait]
    impl Engine for FakeEngine {
        async fn run(&self, mut audio: Audio, callback: Partial) -> Result<String> {
            let mut n = 0;
            while let Some(frame) = audio.recv().await {
                n += frame.len();
                callback("合成预览");
            }
            Ok(format!("samples:{n}"))
        }
    }

    #[tokio::test]
    async fn consumes_valid_pcm_without_network_or_model() {
        let mut session = RecognitionSession::from_engine(SessionId(21), Box::new(FakeEngine));
        assert!(session.push(&[1; 1_600]).is_ok());
        assert!(session.push(&[2; 800]).is_ok());
        assert_eq!(session.finish().await.unwrap(), "samples:2400");
    }

    #[tokio::test]
    async fn empty_and_silent_pcm_cannot_commit_transcript() {
        let empty = RecognitionSession::from_engine(SessionId(22), Box::new(FakeEngine));
        assert_eq!(empty.finish().await.unwrap(), "");
        let mut silent = RecognitionSession::from_engine(SessionId(23), Box::new(FakeEngine));
        silent.push(&[0; 1_600]).unwrap();
        assert_eq!(silent.finish().await.unwrap(), "");
    }

    #[tokio::test]
    async fn bad_frames_and_lifetime_limits_are_rejected() {
        let mut s = RecognitionSession::from_engine(SessionId(24), Box::new(FakeEngine));
        assert!(s.push(&[]).is_err());
        assert!(s.push(&[0; 1_601]).is_err());
        // Exactly sixty seconds are allowed, but not one additional sample.
        for _ in 0..600 {
            loop {
                match s.push(&[1; 1_600]) {
                    Ok(()) => break,
                    Err(e) if e.to_string() == "voicekey_audio_backpressure" => {
                        tokio::task::yield_now().await;
                    }
                    Err(e) => panic!("unexpected push error: {e}"),
                }
            }
        }
        assert!(s.push(&[1]).is_err());
        assert_eq!(s.finish().await.unwrap(), "samples:960000");
    }

    #[tokio::test]
    async fn cancel_prevents_further_input() {
        let mut s = RecognitionSession::from_engine(SessionId(25), Box::new(FakeEngine));
        s.cancel();
        assert!(s.push(&[1]).is_err());
    }

    #[test]
    fn default_voicekey_adapter_is_offline_only() {
        assert!(RecognizerChoice::WechatOffline { model_dir: PathBuf::from("/private/test") }.is_offline());
    }
}
