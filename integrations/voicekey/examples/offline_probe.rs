// SPDX-License-Identifier: GPL-3.0-only
//! Model-driven compatibility probe. Only run via scripts/voicekey_cpu_probe.py,
//! which verifies the independent model lock and fixed synthetic PCM first.
use anyhow::{bail, Result};
use serde_json::{json, Value};
use std::{
    path::Path,
    thread,
    time::{Duration, Instant},
};
use zapime_voicekey_adapter::{
    Cancellation, RecognizerChoice, SessionId, MAX_FRAME_SAMPLES, MAX_SAMPLES,
};

fn millis(begin: Instant) -> f64 {
    begin.elapsed().as_secs_f64() * 1000.0
}

fn recognize(model: &Path, pcm: &[i16], id: u64, paced: bool, cancel: bool) -> Result<Value> {
    let load_begin = Instant::now();
    let session = RecognizerChoice::WechatOffline {
        model_dir: model.into(),
    }
    .start(SessionId(id), Cancellation::default())?;
    let load_ms = millis(load_begin);
    let begin = Instant::now();
    let mut accepted = 0;
    let mut first_partial = None;
    let mut first_audio = None;
    let mut partial_count = 0;
    let mut partial_nonempty = false;
    for block in pcm.chunks(MAX_FRAME_SAMPLES) {
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            match session.push(block) {
                Ok(()) => break,
                Err(error) if error.to_string() == "voicekey_audio_backpressure" => {
                    if Instant::now() >= deadline {
                        bail!("probe_backpressure_timeout");
                    }
                    thread::sleep(Duration::from_millis(1));
                }
                Err(error) => return Err(error),
            }
        }
        accepted += block.len();
        while let Some(event) = session.poll_partial() {
            if event.session != SessionId(id) {
                bail!("probe_partial_session");
            }
            partial_count += 1;
            if !event.text.is_empty() {
                partial_nonempty = true;
                if first_partial.is_none() {
                    first_partial = Some(millis(begin));
                    first_audio = Some(accepted);
                }
            }
        }
        if cancel {
            break;
        }
        if paced {
            thread::sleep(Duration::from_secs_f64(block.len() as f64 / 16000.0));
        }
    }
    let evidence = session.has_nonzero_pcm();
    if cancel {
        let cancellation = Instant::now();
        session.cancel();
        let drained = session.cancel_and_wait(Duration::from_secs(30));
        let rejected = session.push(&[1]).is_err();
        let no_final = session.finish(Duration::from_secs(1)).is_err();
        if !drained || !rejected || !no_final {
            bail!("probe_cancel_not_drained");
        }
        return Ok(
            json!({"id":"cancel_after_first_frame","accepted_samples":accepted,
            "physical_worker_drained":drained,"new_pcm_rejected":rejected,"final_rejected":no_final,
            "cancel_to_drained_ms":millis(cancellation),"scope":"Cancellation after first accepted frame; not a forced mid-matrix pause."}),
        );
    }
    let finish_begin = Instant::now();
    let text = session.finish(Duration::from_secs(30))?;
    let finish_ms = millis(finish_begin);
    let elapsed_ms = millis(begin);
    let nonzero = pcm.iter().any(|&sample| sample != 0);
    if evidence != nonzero || (!nonzero && (!text.is_empty() || partial_nonempty)) {
        bail!("probe_pcm_evidence");
    }
    Ok(
        json!({"accepted_samples":accepted,"has_nonzero_pcm":evidence,"partial_count":partial_count,
        "partial_nonempty":partial_nonempty,"first_partial_observed_ms":first_partial,
        "first_partial_observed_after_accepted_samples":first_audio,"model_start_ms":load_ms,
        "finish_call_ms":finish_ms,"feed_and_finish_ms":elapsed_ms,"text":text}),
    )
}

fn main_result() -> Result<Value> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 4 {
        bail!("usage_model_pcm_rounds_pacing");
    }
    let model = Path::new(&args[0]);
    let input = Path::new(&args[1]);
    if !model.is_absolute() || !input.is_absolute() {
        bail!("absolute_probe_paths_required");
    }
    let rounds: u64 = args[2]
        .to_str()
        .ok_or_else(|| anyhow::anyhow!("invalid_rounds"))?
        .parse()?;
    if !(1..=10).contains(&rounds) {
        bail!("invalid_rounds");
    }
    let paced = args[3] == "paced";
    if !paced && args[3] != "unpaced" {
        bail!("invalid_pacing");
    }
    let size = std::fs::metadata(input)?.len();
    if size == 0 || size > (MAX_SAMPLES * 2) as u64 || size % 2 != 0 {
        bail!("invalid_pcm_size");
    }
    let bytes = std::fs::read(input)?;
    if bytes.len() as u64 != size {
        bail!("pcm_changed");
    }
    let pcm: Vec<_> = bytes
        .chunks_exact(2)
        .map(|x| i16::from_le_bytes([x[0], x[1]]))
        .collect();
    if !pcm.iter().any(|&s| s != 0) {
        bail!("fixture_must_contain_nonzero_pcm");
    }
    let validation_begin = Instant::now();
    zapime_voicekey_adapter::validate_model(model)?;
    let validation_ms = millis(validation_begin);
    let mut cases = Vec::new();
    let mut id = 1;
    for (name, samples) in [
        ("no_samples", vec![]),
        ("all_zero", vec![0; 3200]),
        ("positive_lsb", {
            let mut p = vec![0; 1601];
            p[0] = 1;
            p
        }),
        ("negative_lsb", {
            let mut p = vec![0; 1601];
            p[0] = -1;
            p
        }),
    ] {
        let mut row = recognize(model, &samples, id, false, false)?;
        row["id"] = json!(name);
        cases.push(row);
        id += 1;
    }
    cases.push(recognize(model, &pcm, id, false, true)?);
    id += 1;
    let mut runs = Vec::new();
    for mode in ["release_before_start", "repeated_start"] {
        for round in 1..=rounds {
            if mode == "release_before_start" && !zapime_voicekey_adapter::release_cache() {
                bail!("probe_cache_busy");
            }
            let mut row = recognize(model, &pcm, id, paced, false)?;
            id += 1;
            row["mode"] = json!(mode);
            row["round"] = json!(round);
            runs.push(row);
        }
    }
    if !zapime_voicekey_adapter::release_cache() {
        bail!("probe_final_cache_busy");
    }
    Ok(
        json!({"schema_version":1,"engine_id":"voicekey","complete":true,
        "uncached_model_validation_ms":validation_ms,"cases":cases,"runs":runs,
        "configured_model_threads":thread::available_parallelism().map_or(2,|n|n.get()).min(4),
        "thread_policy":"Pinned upstream Model::forward caps its Rayon pool at available_parallelism.min(4).",
        "pacing":if paced {"realtime_100ms_frames"} else {"unpaced_100ms_frames"},
        "cache_scope":"Every completed physical worker releases model weights. Both series start a model anew, repeated_start does not retain an engine cache. OS page cache is warm after independent integrity/model validation; not phone cold-start.",
        "latency_scope":"Process compute and polling observation; excludes microphone, Android/JNI/editor and user confirmation."}),
    )
}

fn main() {
    match main_result() {
        Ok(value) => println!("{value}"),
        Err(_) => {
            eprintln!("VoiceKey model probe failed; no transcript or model path logged");
            std::process::exit(1);
        }
    }
}
