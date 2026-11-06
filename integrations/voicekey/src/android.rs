// SPDX-License-Identifier: GPL-3.0-only
//! Android JNI boundary for the optional VoiceKey WeChat local recognizer.
//! Only the ZapIME voice worker may own allocate/start/accept/finish/close.
//! cancel() can be called from any thread. No network calls, editor access
//! or third-party model downloads are performed here.
use crate::{RecognitionSession, RecognizerChoice, SessionId};
use jni::objects::{JByteArray, JClass, JShortArray};
use jni::sys::{jboolean, jbyteArray, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::ThreadId;
use std::time::Duration;

struct Handle {
    owner: ThreadId,
    cancelled: AtomicBool,
    session: Mutex<Option<RecognitionSession>>,
    started: AtomicBool,
}

static NEXT: AtomicI64 = AtomicI64::new(1);
static HANDLES: OnceLock<Mutex<HashMap<i64, Arc<Handle>>>> = OnceLock::new();
static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
fn handles() -> &'static Mutex<HashMap<i64, Arc<Handle>>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("VoiceKey runtime cannot start")
    })
}
fn find(id: jlong) -> Option<Arc<Handle>> {
    handles().lock().ok()?.get(&id).cloned()
}
fn owned(id: jlong) -> Option<Arc<Handle>> {
    let handle = find(id)?;
    (handle.owner == std::thread::current().id()).then_some(handle)
}
fn response(env: &JNIEnv, value: Value) -> jbyteArray {
    let Ok(raw) = serde_json::to_vec(&value) else {
        return std::ptr::null_mut();
    };
    if raw.len() > 524_288 {
        return std::ptr::null_mut();
    }
    env.byte_array_from_slice(&raw).map_or(std::ptr::null_mut(), |array| array.into_raw())
}
fn fail(env: &JNIEnv, reason: &str) -> jbyteArray {
    response(env, json!({"error":reason}))
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_allocate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let Ok(mut pool) = handles().lock() else { return 0 };
    if pool.len() >= 4 { return 0; }
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    if id <= 0 { return 0; }
    pool.insert(id, Arc::new(Handle {
        owner: std::thread::current().id(),
        cancelled: AtomicBool::new(false),
        session: Mutex::new(None),
        started: AtomicBool::new(false),
    }));
    id
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_start(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    model_path: JByteArray,
    _runtime_path: JByteArray,
) -> jbyteArray {
    let Some(handle) = owned(id) else { return fail(&env, "invalid_owner") };
    if handle.cancelled.load(Ordering::Acquire) || handle.started.swap(true, Ordering::AcqRel) {
        return fail(&env, "invalid_session");
    }
    let Ok(path_bytes) = env.convert_byte_array(model_path) else {
        return fail(&env, "invalid_model_path");
    };
    if path_bytes.is_empty() || path_bytes.len() > 4096 || path_bytes.contains(&0) {
        return fail(&env, "invalid_model_path");
    }
    let Ok(path_text) = String::from_utf8(path_bytes) else {
        return fail(&env, "invalid_model_path");
    };
    let path = PathBuf::from(path_text);
    if !path.is_absolute() || !path.is_dir() {
        return fail(&env, "model_missing");
    }
    // Model loading happens on ZapIME's dedicated voice worker, not the IME UI.
    // It verifies format and caches a shared model before opening the session.
    if crate::load_model(&path).is_err() {
        return fail(&env, "invalid_voicekey_model");
    }
    if handle.cancelled.load(Ordering::Acquire) {
        return fail(&env, "cancelled");
    }
    let choice = RecognizerChoice::WechatOffline { model_dir: path };
    let _guard = runtime().enter();
    let session = choice.start(SessionId(id as u64));
    let Ok(mut slot) = handle.session.lock() else {
        return fail(&env, "voicekey_lock");
    };
    if handle.cancelled.load(Ordering::Acquire) {
        return fail(&env, "cancelled");
    }
    *slot = Some(session);
    response(&env, json!({"ready":true}))
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_accept(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    pcm: JShortArray,
    count: jint,
) -> jbyteArray {
    let Some(handle) = owned(id) else { return fail(&env, "invalid_owner") };
    if handle.cancelled.load(Ordering::Acquire) {
        return fail(&env, "cancelled");
    }
    if count <= 0 || count > crate::MAX_FRAME_SAMPLES as jint {
        return fail(&env, "invalid_audio");
    }
    let Ok(len) = env.get_array_length(&pcm) else { return fail(&env, "invalid_audio") };
    if count > len { return fail(&env, "invalid_audio"); }
    let mut buf = vec![0i16; count as usize];
    if env.get_short_array_region(&pcm, 0, &mut buf).is_err() {
        return fail(&env, "invalid_audio");
    }
    let Ok(mut slot) = handle.session.lock() else { return fail(&env, "voicekey_lock") };
    let Some(session) = slot.as_mut() else { return fail(&env, "invalid_session") };
    if let Err(err) = session.push(&buf) {
        return fail(&env, &err.to_string());
    }
    let mut latest = String::new();
    while let Some(result) = session.poll_partial() {
        if result.session == SessionId(id as u64) {
            latest = result.text;
        }
    }
    response(&env, json!({"text":latest,"has_nonzero_pcm":true}))
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_finish(
    env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jbyteArray {
    let Some(handle) = owned(id) else { return fail(&env, "invalid_owner") };
    if handle.cancelled.load(Ordering::Acquire) { return fail(&env, "cancelled"); }
    let session = {
        let Ok(mut slot) = handle.session.lock() else { return fail(&env, "voicekey_lock") };
        slot.take()
    };
    let Some(session) = session else { return fail(&env, "invalid_session") };
    let completed = runtime().block_on(async {
        tokio::time::timeout(Duration::from_secs(30), session.finish()).await
    });
    if handle.cancelled.load(Ordering::Acquire) { return fail(&env, "cancelled"); }
    match completed {
        Ok(Ok(text)) => response(&env, json!({"text":text})),
        Ok(Err(_)) => fail(&env, "decode_failure"),
        Err(_) => fail(&env, "decode_timeout"),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_cancel(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    if let Some(handle) = find(id) {
        handle.cancelled.store(true, Ordering::Release);
        if let Ok(mut slot) = handle.session.try_lock() {
            if let Some(session) = slot.as_mut() { session.cancel(); }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_close(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    let Some(handle) = owned(id) else { return JNI_FALSE };
    handle.cancelled.store(true, Ordering::Release);
    if let Ok(mut slot) = handle.session.lock() {
        if let Some(session) = slot.as_mut() { session.cancel(); }
        slot.take();
    }
    if let Ok(mut pool) = handles().lock() { pool.remove(&id); }
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_checkModel(
    mut env: JNIEnv,
    _class: JClass,
    directory: jni::objects::JString,
) -> jboolean {
    let Ok(dir) = env.get_string(&directory) else { return JNI_FALSE };
    let path = PathBuf::from(dir.to_string_lossy().into_owned());
    if !path.is_absolute() || !path.is_dir() { return JNI_FALSE; }
    if crate::load_model(&path).is_ok() { JNI_TRUE } else { JNI_FALSE }
}
