// SPDX-License-Identifier: GPL-3.0-only
//! Optional offline engine. One physical decoder slot remains occupied until
//! the blocking worker exits; cancellation is cooperative, never task abort.
#[cfg(target_os = "android")]
mod android;
#[path = "../../../vendor/voicekey/crates/wtlocal/src/fbank.rs"]
#[rustfmt::skip]
mod fbank;
mod kernels;
mod model;
mod stream;
#[path = "../../../vendor/voicekey/crates/wtlocal/src/xnet.rs"]
#[rustfmt::skip]
mod xnet;

use anyhow::{anyhow, bail, Result};
use model::Model;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use stream::Stream;

pub const MAX_SAMPLES: usize = 960_000;
pub const MAX_FRAME_SAMPLES: usize = 1_600;
const MAX_TEXT_BYTES: usize = 65_536;
const PARTIAL_EVERY: usize = 30;
const AUDIO_QUEUE: usize = 8;

/// The JNI caller supplies its remaining overall drain budget. Invalid values
/// must be rejected before a handle becomes one-shot finished, never expanded.
#[cfg(any(target_os = "android", test))]
fn checked_finish_timeout_ms(timeout_ms: i32) -> Result<Duration> {
    if !(1..=30_000).contains(&timeout_ms) {
        bail!("invalid_timeout");
    }
    Ok(Duration::from_millis(timeout_ms as u64))
}

#[derive(Clone, Default)]
pub struct Cancellation(Arc<AtomicBool>);
impl Cancellation {
    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }
    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
    pub fn check(&self) -> Result<()> {
        if self.is_cancelled() {
            bail!("cancelled");
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct FileIdentity {
    path: PathBuf,
    size: u64,
    modified: std::time::SystemTime,
    #[cfg(unix)]
    inode: (u64, u64),
}
impl FileIdentity {
    fn read(path: &Path) -> Result<Self> {
        let meta = std::fs::symlink_metadata(path)?;
        if !meta.is_file() || meta.file_type().is_symlink() {
            bail!("invalid_model_file");
        }
        Ok(Self {
            path: path.canonicalize()?,
            size: meta.len(),
            modified: meta.modified()?,
            #[cfg(unix)]
            inode: {
                use std::os::unix::fs::MetadataExt;
                (meta.dev(), meta.ino())
            },
        })
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct ModelIdentity(Vec<FileIdentity>);
impl ModelIdentity {
    fn read(dir: &Path) -> Result<Self> {
        let meta = std::fs::symlink_metadata(dir)?;
        if !dir.is_absolute() || !meta.is_dir() || meta.file_type().is_symlink() {
            bail!("invalid_model_path");
        }
        let mut files = Vec::new();
        for (index, entry) in std::fs::read_dir(dir)?.enumerate() {
            if index >= 16 {
                bail!("invalid_model_files");
            }
            let path = entry?.path();
            if path.extension().is_some_and(|x| x == "xnet") {
                files.push(FileIdentity::read(&path)?);
            }
        }
        if files.len() != 1 || files[0].size == 0 || files[0].size > 512 * 1024 * 1024 {
            bail!("invalid_model_files");
        }
        let dictionary = FileIdentity::read(&dir.join("dict.decoder.utf8.txt"))?;
        if dictionary.size == 0 || dictionary.size > 16 * 1024 * 1024 {
            bail!("invalid_model_dictionary");
        }
        files.push(dictionary);
        Ok(Self(files))
    }
}
struct Cached {
    identity: ModelIdentity,
    model: Arc<Model>,
}
static MODEL_CACHE: Mutex<Option<Cached>> = Mutex::new(None);
fn load_model(dir: &Path, cancel: &Cancellation) -> Result<Arc<Model>> {
    cancel.check()?;
    // Identity detects app-private atomic replacement; the Java importer still
    // authenticates the fixed file SHA allowlist before calling any parser.
    let identity = ModelIdentity::read(dir)?;
    {
        let cache = MODEL_CACHE.lock().map_err(|_| anyhow!("model_lock"))?;
        cancel.check()?;
        if let Some(old) = cache.as_ref() {
            if old.identity == identity {
                return Ok(old.model.clone());
            }
        }
    }
    // Parsing untrusted files must never poison the shared cache mutex. Catch
    // parser panics as a second boundary behind trusted SHA verification.
    let model = Arc::new(load_uncached(dir, cancel)?);
    cancel.check()?;
    if identity != ModelIdentity::read(dir)? {
        bail!("model_changed_during_load");
    }
    let mut cache = MODEL_CACHE.lock().map_err(|_| anyhow!("model_lock"))?;
    *cache = Some(Cached {
        identity,
        model: model.clone(),
    });
    Ok(model)
}
fn load_uncached(dir: &Path, cancel: &Cancellation) -> Result<Model> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        Model::load_checked(dir, cancel)
    }))
    .map_err(|_| anyhow!("invalid_model_parser_panic"))?
}
/// Validation is deliberately uncached: a staging model must not displace the
/// active model, and each validated mmap is dropped before the importer moves it.
pub fn validate_model(dir: &Path) -> Result<()> {
    let before = ModelIdentity::read(dir)?;
    let _model = load_uncached(dir, &Cancellation::default())?;
    if before != ModelIdentity::read(dir)? {
        bail!("model_changed_during_load");
    }
    Ok(())
}
/// Explicit memory-pressure/idle release. Never evict an in-use model.
pub fn release_cache() -> bool {
    let Ok(mut cache) = MODEL_CACHE.try_lock() else {
        return false;
    };
    if cache
        .as_ref()
        .is_some_and(|x| Arc::strong_count(&x.model) > 1)
    {
        return false;
    }
    cache.take();
    true
}

#[derive(Clone)]
struct Capacity(Arc<AtomicUsize>);
impl Capacity {
    fn acquire(&self) -> Result<Permit> {
        self.0
            .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| anyhow!("voicekey_decoder_busy"))?;
        Ok(Permit(self.0.clone()))
    }
}
struct Permit(Arc<AtomicUsize>);
impl Drop for Permit {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}
fn capacity() -> &'static Capacity {
    static SLOT: OnceLock<Capacity> = OnceLock::new();
    SLOT.get_or_init(|| Capacity(Arc::new(AtomicUsize::new(0))))
}

pub trait RecognizerEngine: Send + 'static {
    fn run(
        &self,
        audio: mpsc::Receiver<Vec<i16>>,
        partial: Box<dyn Fn(&str) + Send>,
        cancel: &Cancellation,
    ) -> Result<String>;
}
struct VoiceKeyOfflineEngine {
    model: Arc<Model>,
}
impl RecognizerEngine for VoiceKeyOfflineEngine {
    fn run(
        &self,
        audio: mpsc::Receiver<Vec<i16>>,
        partial: Box<dyn Fn(&str) + Send>,
        cancel: &Cancellation,
    ) -> Result<String> {
        run_audio(
            Box::new(Stream::new(self.model.clone())),
            audio,
            partial,
            cancel,
        )
    }
}
trait Decoder: Send {
    fn push(&mut self, pcm: &[i16], nonzero: bool, cancel: &Cancellation) -> Result<()>;
    fn fresh(&self) -> usize;
    fn partial(&mut self, cancel: &Cancellation) -> Result<String>;
    fn finish(self: Box<Self>, cancel: &Cancellation) -> Result<String>;
}
impl Decoder for Stream {
    fn push(&mut self, pcm: &[i16], nonzero: bool, cancel: &Cancellation) -> Result<()> {
        self.push(pcm, nonzero, cancel)
    }
    fn fresh(&self) -> usize {
        self.fresh()
    }
    fn partial(&mut self, cancel: &Cancellation) -> Result<String> {
        self.partial(cancel)
    }
    fn finish(self: Box<Self>, cancel: &Cancellation) -> Result<String> {
        (*self).finish(cancel)
    }
}
fn run_audio(
    mut session: Box<dyn Decoder>,
    audio: mpsc::Receiver<Vec<i16>>,
    partial: Box<dyn Fn(&str) + Send>,
    cancel: &Cancellation,
) -> Result<String> {
    let mut nonzero = false;
    let mut last = String::new();
    let (mut previous, mut duration) = (Instant::now(), Duration::ZERO);
    loop {
        cancel.check()?;
        let frame = match audio.recv_timeout(Duration::from_millis(20)) {
            Ok(frame) => frame,
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        };
        nonzero |= frame.iter().any(|x| *x != 0);
        session.push(&frame, nonzero, cancel)?;
        // Only feature extraction runs for exact-zero input. Do not call a
        // model partial or final decoder without accepted nonzero evidence.
        if nonzero && session.fresh() >= PARTIAL_EVERY && previous.elapsed() >= duration * 3 {
            let started = Instant::now();
            let text = session.partial(cancel)?;
            duration = started.elapsed();
            previous = Instant::now();
            cancel.check()?;
            if !text.is_empty() && text != last {
                partial(&text);
                last = text;
            }
        }
    }
    cancel.check()?;
    if nonzero {
        session.finish(cancel)
    } else {
        Ok(String::new())
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
    WechatOffline { model_dir: PathBuf },
}
impl RecognizerChoice {
    pub fn is_offline(&self) -> bool {
        true
    }
    pub fn start(&self, id: SessionId, cancel: Cancellation) -> Result<RecognitionSession> {
        let permit = capacity().acquire()?;
        let Self::WechatOffline { model_dir } = self;
        let model = load_model(model_dir, &cancel)?;
        RecognitionSession::spawn(
            id,
            Box::new(VoiceKeyOfflineEngine { model }),
            cancel,
            Some(permit),
        )
    }
}
struct Input {
    sender: Option<mpsc::SyncSender<Vec<i16>>>,
    accepted: usize,
    nonzero: bool,
}
struct Shared {
    cancel: Cancellation,
    partial: Mutex<Option<PartialResult>>,
    result: Mutex<Option<std::result::Result<String, String>>>,
    ready: Condvar,
}
pub struct RecognitionSession {
    id: SessionId,
    input: Mutex<Input>,
    shared: Arc<Shared>,
    worker: Mutex<Option<JoinHandle<()>>>,
}
impl RecognitionSession {
    fn spawn(
        id: SessionId,
        engine: Box<dyn RecognizerEngine>,
        cancel: Cancellation,
        permit: Option<Permit>,
    ) -> Result<Self> {
        let (tx, rx) = mpsc::sync_channel(AUDIO_QUEUE);
        let shared = Arc::new(Shared {
            cancel,
            partial: Mutex::new(None),
            result: Mutex::new(None),
            ready: Condvar::new(),
        });
        let output = shared.clone();
        let worker = std::thread::Builder::new()
            .name("zapime-voicekey-decode".into())
            .spawn(move || {
                // This permit is owned by the physical blocking worker, including
                // while it winds down after timeout or a caller drops its session.
                let _permit = permit;
                let callback_output = output.clone();
                let callback = Box::new(move |value: &str| {
                    if !callback_output.cancel.is_cancelled()
                        && !value.is_empty()
                        && value.len() <= MAX_TEXT_BYTES
                    {
                        if let Ok(mut slot) = callback_output.partial.lock() {
                            *slot = Some(PartialResult {
                                session: id,
                                text: value.to_owned(),
                            });
                        }
                    }
                });
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    engine.run(rx, callback, &output.cancel)
                }));
                let result = if output.cancel.is_cancelled() {
                    Err("cancelled".into())
                } else {
                    match outcome {
                        Ok(Ok(text)) if text.len() <= MAX_TEXT_BYTES => Ok(text),
                        Ok(Ok(_)) => Err("transcript_limit".into()),
                        Ok(Err(error)) => Err(error.to_string()),
                        Err(_) => Err("voicekey_worker_panicked".into()),
                    }
                };
                drop(engine); // Model/mmap released before completion is observed.
                release_cache();
                if let Ok(mut slot) = output.result.lock() {
                    *slot = Some(result);
                    output.ready.notify_all();
                }
            })?;
        Ok(Self {
            id,
            input: Mutex::new(Input {
                sender: Some(tx),
                accepted: 0,
                nonzero: false,
            }),
            shared,
            worker: Mutex::new(Some(worker)),
        })
    }
    #[cfg(test)]
    fn from_engine(id: SessionId, engine: Box<dyn RecognizerEngine>) -> Self {
        Self::spawn(id, engine, Cancellation::default(), None).unwrap()
    }
    pub fn session_id(&self) -> SessionId {
        self.id
    }
    pub fn has_nonzero_pcm(&self) -> bool {
        self.input.lock().is_ok_and(|x| x.nonzero)
    }
    pub fn push(&self, samples: &[i16]) -> Result<()> {
        self.shared.cancel.check()?;
        if samples.is_empty() || samples.len() > MAX_FRAME_SAMPLES {
            bail!("invalid_pcm_frame");
        }
        let mut input = self.input.lock().map_err(|_| anyhow!("voicekey_lock"))?;
        let count = input
            .accepted
            .checked_add(samples.len())
            .ok_or_else(|| anyhow!("audio_limit"))?;
        if count > MAX_SAMPLES {
            bail!("audio_limit");
        }
        let tx = input
            .sender
            .as_ref()
            .ok_or_else(|| anyhow!("session_closed"))?;
        tx.try_send(samples.to_vec()).map_err(|e| match e {
            mpsc::TrySendError::Full(_) => anyhow!("voicekey_audio_backpressure"),
            mpsc::TrySendError::Disconnected(_) => anyhow!("voicekey_engine_stopped"),
        })?;
        input.accepted = count;
        input.nonzero |= samples.iter().any(|x| *x != 0);
        Ok(())
    }
    /// Worker-only bounded backpressure; UI/key threads must not call this.
    /// `push` keeps its nonblocking/full contract. No capacity or evidence is
    /// added until the complete prefix is actually accepted by the channel.
    pub fn push_wait(&self, samples: &[i16], timeout: Duration) -> Result<()> {
        let deadline = Instant::now()
            .checked_add(timeout)
            .ok_or_else(|| anyhow!("invalid_accept_timeout"))?;
        let mut attempted = false;
        loop {
            self.shared.cancel.check()?;
            if attempted && Instant::now() >= deadline {
                bail!("voicekey_audio_backpressure");
            }
            attempted = true;
            match self.push(samples) {
                Ok(()) => return Ok(()),
                Err(error) if error.to_string() == "voicekey_audio_backpressure" => {}
                Err(error) => return Err(error),
            }
            self.shared.cancel.check()?;
            let now = Instant::now();
            if now >= deadline {
                bail!("voicekey_audio_backpressure");
            }
            // No ingress/handle mutex spans this wait. The completion/cancel
            // notification accelerates exit; short bounded polls notice a
            // receiver freeing capacity without expanding the native queue.
            let pause = (deadline - now).min(Duration::from_millis(10));
            let slot = self
                .shared
                .result
                .lock()
                .map_err(|_| anyhow!("voicekey_lock"))?;
            let (_slot, _) = self
                .shared
                .ready
                .wait_timeout_while(slot, pause, |value| {
                    value.is_none() && !self.shared.cancel.is_cancelled()
                })
                .map_err(|_| anyhow!("voicekey_lock"))?;
        }
    }
    pub fn poll_partial(&self) -> Option<PartialResult> {
        if self.shared.cancel.is_cancelled() || !self.has_nonzero_pcm() {
            return None;
        }
        self.shared.partial.lock().ok()?.take()
    }
    fn wait(&self, timeout: Duration) -> Result<Option<std::result::Result<String, String>>> {
        let slot = self
            .shared
            .result
            .lock()
            .map_err(|_| anyhow!("voicekey_lock"))?;
        let (slot, _) = self
            .shared
            .ready
            .wait_timeout_while(slot, timeout, |x| x.is_none())
            .map_err(|_| anyhow!("voicekey_lock"))?;
        Ok(slot.clone())
    }
    pub fn finish(&self, timeout: Duration) -> Result<String> {
        self.input
            .lock()
            .map_err(|_| anyhow!("voicekey_lock"))?
            .sender
            .take();
        let Some(result) = self.wait(timeout)? else {
            self.cancel();
            self.wait(Duration::from_secs(2))?;
            bail!("decode_timeout");
        };
        self.join_completed();
        self.shared.cancel.check()?;
        let text = result.map_err(|e| anyhow!(e))?;
        Ok(if self.has_nonzero_pcm() {
            text
        } else {
            String::new()
        })
    }
    fn join_completed(&self) {
        if let Ok(mut worker) = self.worker.lock() {
            if self.shared.result.lock().is_ok_and(|x| x.is_some()) {
                if let Some(worker) = worker.take() {
                    let _ = worker.join();
                }
            }
        }
    }
    pub fn cancel(&self) {
        self.shared.cancel.cancel();
        self.shared.ready.notify_all();
        if let Ok(mut input) = self.input.lock() {
            input.sender.take();
        }
        if let Ok(mut partial) = self.shared.partial.lock() {
            partial.take();
        }
    }
    /// False means the cooperative worker is still winding down, not killed.
    pub fn cancel_and_wait(&self, timeout: Duration) -> bool {
        self.cancel();
        let done = self.wait(timeout).ok().flatten().is_some();
        if done {
            self.join_completed();
        }
        done
    }
}
impl Drop for RecognitionSession {
    fn drop(&mut self) {
        self.cancel();
        self.join_completed();
    }
}

#[cfg(test)]
mod tests;
