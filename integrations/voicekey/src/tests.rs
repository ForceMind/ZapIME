// SPDX-License-Identifier: GPL-3.0-only
//! Synthetic control and numerical tests, not model recognition or accuracy.
use super::*;
struct Counting;
impl RecognizerEngine for Counting {
    fn run(
        &self,
        audio: mpsc::Receiver<Vec<i16>>,
        partial: Box<dyn Fn(&str) + Send>,
        cancel: &Cancellation,
    ) -> Result<String> {
        let mut n = 0;
        while let Ok(frame) = audio.recv() {
            cancel.check()?;
            n += frame.len();
            partial("synthetic preview");
        }
        cancel.check()?;
        Ok(format!("samples:{n}"))
    }
}
fn finish(s: &RecognitionSession) -> String {
    s.finish(Duration::from_secs(5)).unwrap()
}
#[test]
fn accepted_pcm_tail_and_session_isolation() {
    let s = RecognitionSession::from_engine(SessionId(21), Box::new(Counting));
    s.push(&[1; 1600]).unwrap();
    s.push(&[0; 137]).unwrap();
    assert!(s.has_nonzero_pcm());
    assert_eq!(finish(&s), "samples:1737");
    assert!(s.push(&[1]).is_err());
    let next = RecognitionSession::from_engine(SessionId(22), Box::new(Counting));
    assert!(!next.has_nonzero_pcm());
    assert_eq!(finish(&next), "");
}
#[test]
fn empty_and_zero_input_suppress_misbehaving_engine_results() {
    let empty = RecognitionSession::from_engine(SessionId(1), Box::new(Counting));
    assert_eq!(finish(&empty), "");
    let silent = RecognitionSession::from_engine(SessionId(2), Box::new(Counting));
    silent.push(&[0; 1600]).unwrap();
    assert_eq!(finish(&silent), "");
    assert!(silent.poll_partial().is_none());
    assert!(!silent.has_nonzero_pcm());
}
#[test]
fn frame_bounds_and_failed_push_cannot_create_nonzero_evidence() {
    let s = RecognitionSession::from_engine(SessionId(1), Box::new(Counting));
    assert!(s.push(&[]).is_err());
    assert!(s.push(&[1; 1601]).is_err());
    assert!(!s.has_nonzero_pcm());
    assert!(s.cancel_and_wait(Duration::from_secs(2)));
    assert!(s.push(&[1]).is_err());
    assert!(!s.has_nonzero_pcm());
    assert!(s.poll_partial().is_none());
}
struct Held(Arc<(Mutex<bool>, Condvar)>);
impl RecognizerEngine for Held {
    fn run(
        &self,
        _audio: mpsc::Receiver<Vec<i16>>,
        partial: Box<dyn Fn(&str) + Send>,
        _cancel: &Cancellation,
    ) -> Result<String> {
        let (lock, cv) = &*self.0;
        let g = lock.lock().unwrap();
        let _guard = cv.wait_while(g, |x| !*x).unwrap();
        partial("late synthetic result");
        Ok("late synthetic final".into())
    }
}
fn release(pair: &Arc<(Mutex<bool>, Condvar)>) {
    let (lock, cv) = &**pair;
    *lock.lock().unwrap() = true;
    cv.notify_all();
}
#[test]
fn full_audio_queue_is_explicit_and_never_counts_rejected_frame() {
    let pair = Arc::new((Mutex::new(false), Condvar::new()));
    let s = RecognitionSession::from_engine(SessionId(1), Box::new(Held(pair.clone())));
    for _ in 0..AUDIO_QUEUE {
        s.push(&[0; 1600]).unwrap();
    }
    assert_eq!(
        s.push(&[1]).unwrap_err().to_string(),
        "voicekey_audio_backpressure"
    );
    assert!(!s.has_nonzero_pcm());
    release(&pair);
    assert_eq!(finish(&s), "");
}
#[test]
fn physical_capacity_is_kept_after_timeout_until_real_worker_exit() {
    let gate = Capacity(Arc::new(AtomicUsize::new(0)));
    let pair = Arc::new((Mutex::new(false), Condvar::new()));
    let s = RecognitionSession::spawn(
        SessionId(1),
        Box::new(Held(pair.clone())),
        Cancellation::default(),
        Some(gate.acquire().unwrap()),
    )
    .unwrap();
    s.push(&[1]).unwrap();
    assert_eq!(
        s.finish(Duration::ZERO).unwrap_err().to_string(),
        "decode_timeout"
    );
    assert!(gate.acquire().is_err());
    assert!(s.poll_partial().is_none());
    release(&pair);
    assert!(s.cancel_and_wait(Duration::from_secs(2)));
    assert!(gate.acquire().is_ok());
    assert_eq!(
        s.finish(Duration::ZERO).unwrap_err().to_string(),
        "cancelled"
    );
}
#[test]
fn dropping_session_does_not_release_a_running_decoder_slot() {
    let gate = Capacity(Arc::new(AtomicUsize::new(0)));
    let pair = Arc::new((Mutex::new(false), Condvar::new()));
    let s = RecognitionSession::spawn(
        SessionId(1),
        Box::new(Held(pair.clone())),
        Cancellation::default(),
        Some(gate.acquire().unwrap()),
    )
    .unwrap();
    let shared = s.shared.clone();
    drop(s);
    assert!(gate.acquire().is_err());
    release(&pair);
    let started = Instant::now();
    while gate.acquire().is_err() {
        assert!(started.elapsed() < Duration::from_secs(2));
        std::thread::yield_now();
    }
    assert!(shared.cancel.is_cancelled());
    assert!(shared.partial.lock().unwrap().is_none());
}
#[test]
fn sixty_second_input_limit_preserves_exact_tail() {
    let s = RecognitionSession::from_engine(SessionId(1), Box::new(Counting));
    for _ in 0..600 {
        loop {
            match s.push(&[1; 1600]) {
                Ok(()) => break,
                Err(e) if e.to_string() == "voicekey_audio_backpressure" => {
                    std::thread::yield_now()
                }
                Err(e) => panic!("{e}"),
            }
        }
    }
    assert_eq!(s.push(&[1]).unwrap_err().to_string(), "audio_limit");
    assert_eq!(finish(&s), "samples:960000");
}
struct Panics;
impl RecognizerEngine for Panics {
    fn run(
        &self,
        _audio: mpsc::Receiver<Vec<i16>>,
        _partial: Box<dyn Fn(&str) + Send>,
        _cancel: &Cancellation,
    ) -> Result<String> {
        panic!("synthetic decoder panic")
    }
}
#[test]
fn worker_panic_is_contained_and_releases_physical_capacity() {
    let gate = Capacity(Arc::new(AtomicUsize::new(0)));
    let s = RecognitionSession::spawn(
        SessionId(1),
        Box::new(Panics),
        Cancellation::default(),
        Some(gate.acquire().unwrap()),
    )
    .unwrap();
    assert_eq!(
        s.finish(Duration::from_secs(2)).unwrap_err().to_string(),
        "voicekey_worker_panicked"
    );
    assert!(gate.acquire().is_ok());
}
#[derive(Default)]
struct Calls {
    partial: AtomicUsize,
    finish: AtomicUsize,
    push: AtomicUsize,
}
struct MockDecoder(Arc<Calls>);
impl Decoder for MockDecoder {
    fn push(&mut self, _pcm: &[i16], _nonzero: bool, cancel: &Cancellation) -> Result<()> {
        cancel.check()?;
        self.0.push.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
    fn fresh(&self) -> usize {
        100
    }
    fn partial(&mut self, _cancel: &Cancellation) -> Result<String> {
        self.0.partial.fetch_add(1, Ordering::SeqCst);
        Ok("synthetic partial".into())
    }
    fn finish(self: Box<Self>, _cancel: &Cancellation) -> Result<String> {
        self.0.finish.fetch_add(1, Ordering::SeqCst);
        Ok("synthetic final".into())
    }
}
#[test]
fn production_audio_loop_skips_partial_and_final_decode_for_empty_or_exact_zero() {
    for frames in [vec![], vec![vec![0; 1600], vec![0; 5]]] {
        let calls = Arc::new(Calls::default());
        let (tx, rx) = mpsc::channel();
        for frame in frames {
            tx.send(frame).unwrap();
        }
        drop(tx);
        let result = run_audio(
            Box::new(MockDecoder(calls.clone())),
            rx,
            Box::new(|_| panic!("zero partial escaped")),
            &Cancellation::default(),
        )
        .unwrap();
        assert_eq!(result, "");
        assert_eq!(calls.partial.load(Ordering::SeqCst), 0);
        assert_eq!(calls.finish.load(Ordering::SeqCst), 0);
    }
}
#[test]
fn production_audio_loop_retains_positive_negative_lsb_and_trailing_quiet() {
    for value in [-1, 1, i16::MIN, i16::MAX] {
        let calls = Arc::new(Calls::default());
        let (tx, rx) = mpsc::channel();
        tx.send(vec![value]).unwrap();
        tx.send(vec![0; 1600]).unwrap();
        drop(tx);
        assert_eq!(
            run_audio(
                Box::new(MockDecoder(calls.clone())),
                rx,
                Box::new(|_| {}),
                &Cancellation::default()
            )
            .unwrap(),
            "synthetic final"
        );
        assert_eq!(calls.finish.load(Ordering::SeqCst), 1);
        assert_eq!(calls.push.load(Ordering::SeqCst), 2);
    }
}
#[test]
fn production_audio_loop_cancels_before_any_more_decode() {
    let calls = Arc::new(Calls::default());
    let (tx, rx) = mpsc::channel();
    tx.send(vec![1]).unwrap();
    drop(tx);
    let c = Cancellation::default();
    c.cancel();
    assert!(run_audio(
        Box::new(MockDecoder(calls.clone())),
        rx,
        Box::new(|_| panic!("late partial")),
        &c
    )
    .is_err());
    assert_eq!(calls.push.load(Ordering::SeqCst), 0);
    assert_eq!(calls.finish.load(Ordering::SeqCst), 0);
}
#[test]
fn partial_state_is_latest_only_and_absence_is_not_empty_replacement() {
    let s = RecognitionSession::from_engine(SessionId(5), Box::new(Counting));
    s.push(&[1]).unwrap();
    finish(&s);
    assert_eq!(s.poll_partial().unwrap().text, "synthetic preview");
    assert!(s.poll_partial().is_none());
}
#[test]
fn model_identity_detects_same_path_replacement_and_symlinks() {
    let path = std::env::temp_dir().join(format!(
        "zapime-vk-identity-{}-{:?}",
        std::process::id(),
        std::thread::current().id()
    ));
    std::fs::create_dir(&path).unwrap();
    std::fs::write(path.join("model.xnet"), b"old").unwrap();
    std::fs::write(path.join("dict.decoder.utf8.txt"), b"vocab").unwrap();
    let before = ModelIdentity::read(&path).unwrap();
    std::fs::write(path.join("new.xnet.tmp"), b"newer").unwrap();
    std::fs::rename(path.join("new.xnet.tmp"), path.join("model.xnet")).unwrap();
    assert_ne!(before, ModelIdentity::read(&path).unwrap());
    std::fs::write(path.join("extra.xnet"), b"x").unwrap();
    assert!(ModelIdentity::read(&path).is_err());
    std::fs::remove_file(path.join("extra.xnet")).unwrap();
    #[cfg(unix)]
    {
        std::fs::remove_file(path.join("model.xnet")).unwrap();
        std::os::unix::fs::symlink(path.join("dict.decoder.utf8.txt"), path.join("model.xnet"))
            .unwrap();
        assert!(ModelIdentity::read(&path).is_err());
    }
    std::fs::remove_dir_all(path).unwrap();
}

struct Cooperative(Arc<(Mutex<bool>, Condvar)>);
impl RecognizerEngine for Cooperative {
    fn run(
        &self,
        _audio: mpsc::Receiver<Vec<i16>>,
        _partial: Box<dyn Fn(&str) + Send>,
        cancel: &Cancellation,
    ) -> Result<String> {
        let (lock, cv) = &*self.0;
        *lock.lock().unwrap() = true;
        cv.notify_all();
        loop {
            cancel.check()?;
            std::thread::sleep(Duration::from_millis(1));
        }
    }
}
#[test]
fn cross_thread_cancel_reaches_a_finish_wait_and_joins_real_worker() {
    let gate = Capacity(Arc::new(AtomicUsize::new(0)));
    let entered = Arc::new((Mutex::new(false), Condvar::new()));
    let cancel = Cancellation::default();
    let s = RecognitionSession::spawn(
        SessionId(1),
        Box::new(Cooperative(entered.clone())),
        cancel.clone(),
        Some(gate.acquire().unwrap()),
    )
    .unwrap();
    let caller = std::thread::spawn(move || s.finish(Duration::from_secs(5)));
    let (lock, cv) = &*entered;
    let _guard = cv
        .wait_timeout_while(lock.lock().unwrap(), Duration::from_secs(2), |v| !*v)
        .unwrap();
    cancel.cancel();
    assert_eq!(caller.join().unwrap().unwrap_err().to_string(), "cancelled");
    assert!(gate.acquire().is_ok());
}

#[test]
fn malformed_xnet_never_poison_shared_cache_or_escape_public_validation() {
    let path = std::env::temp_dir().join(format!(
        "zapime-vk-malformed-{}-{:?}",
        std::process::id(),
        std::thread::current().id()
    ));
    std::fs::create_dir(&path).unwrap();
    std::fs::write(path.join("dict.decoder.utf8.txt"), b"0 blank\n").unwrap();
    for n in 4..=9 {
        let mut bytes = b"XNET".to_vec();
        bytes.resize(n, 0);
        std::fs::write(path.join("model.xnet"), bytes).unwrap();
        assert!(load_model(&path, &Cancellation::default()).is_err());
        assert!(validate_model(&path).is_err());
        // This tests the real shared mutex after each malformed parser call;
        // no valid licensed weights are needed to establish poison recovery.
        assert!(!MODEL_CACHE.is_poisoned());
        assert!(MODEL_CACHE.lock().is_ok());
        assert!(release_cache());
    }
    std::fs::remove_dir_all(path).unwrap();
}
#[test]
fn real_fbank_tail_is_chunk_invariant_and_retains_upstream_snip_edges() {
    // Real pinned Fbank, not MockDecoder. Upstream snips incomplete 400-sample
    // windows; no implicit flush/padding is claimed for the final <25ms.
    for n in [0usize, 1, 137, 399, 400, 559, 560, 1599, 1600, 1737] {
        let pcm: Vec<i16> = (0..n)
            .map(|i| ((i * 7919 % 65536) as i32 - 32768) as i16)
            .collect();
        let mut whole = fbank::Fbank::new();
        let mut whole_features = Vec::new();
        whole.push(&pcm, &mut whole_features);
        let mut chunks = fbank::Fbank::new();
        let mut chunk_features = Vec::new();
        for block in pcm.chunks(137) {
            chunks.push(block, &mut chunk_features);
        }
        assert_eq!(chunk_features, whole_features, "n={n}");
        let frames = if n < 400 { 0 } else { (n - 400) / 160 + 1 };
        assert_eq!(whole_features.len(), frames, "n={n}");
        assert!(whole_features
            .iter()
            .flatten()
            .all(|value| value.is_finite()));
    }
}
