// SPDX-License-Identifier: GPL-3.0-only
//! JNI owns session handles on the voice worker; only cancel is cross-thread.
use crate::{Cancellation, RecognitionSession, RecognizerChoice, SessionId};
use jni::objects::{JByteArray, JClass, JShortArray, JString};
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
    cancel: Cancellation,
    session: Mutex<Option<Arc<RecognitionSession>>>,
    started: AtomicBool,
    finished: AtomicBool,
}
static NEXT: AtomicI64 = AtomicI64::new(1);
static HANDLES: OnceLock<Mutex<HashMap<i64, Arc<Handle>>>> = OnceLock::new();
fn handles() -> &'static Mutex<HashMap<i64, Arc<Handle>>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn find(id: jlong) -> Option<Arc<Handle>> {
    handles().lock().ok()?.get(&id).cloned()
}
fn owned(id: jlong) -> Option<Arc<Handle>> {
    let h = find(id)?;
    (h.owner == std::thread::current().id()).then_some(h)
}
fn fail(reason: &str) -> Value {
    json!({"error":reason})
}
fn response(env: &JNIEnv, value: Value) -> jbyteArray {
    let Ok(raw) = serde_json::to_vec(&value) else {
        return std::ptr::null_mut();
    };
    if raw.len() > 524_288 {
        return std::ptr::null_mut();
    }
    env.byte_array_from_slice(&raw)
        .map_or(std::ptr::null_mut(), |x| x.into_raw())
}
fn boundary(env: &mut JNIEnv, call: impl FnOnce(&mut JNIEnv) -> Value) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| call(env)))
        .unwrap_or_else(|_| fail("voicekey_internal_error"));
    // Invalid JNI arguments may leave a Java exception pending. Do not allocate
    // a reply with that exception pending or let it escape into the IME.
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
        return response(env, fail("voicekey_jni_failure"));
    }
    response(env, result)
}
fn session(handle: &Handle) -> Option<Arc<RecognitionSession>> {
    handle.session.lock().ok()?.as_ref().cloned()
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_allocate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    std::panic::catch_unwind(|| {
        let Ok(mut pool) = handles().lock() else {
            return 0;
        };
        if pool.len() >= 4 {
            return 0;
        }
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        if id <= 0 {
            return 0;
        }
        pool.insert(
            id,
            Arc::new(Handle {
                owner: std::thread::current().id(),
                cancel: Cancellation::default(),
                session: Mutex::new(None),
                started: AtomicBool::new(false),
                finished: AtomicBool::new(false),
            }),
        );
        id
    })
    .unwrap_or(0)
}
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_start(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    model_path: JByteArray,
    _runtime_path: JByteArray,
) -> jbyteArray {
    boundary(&mut env, |env| {
        let Some(handle) = owned(id) else {
            return fail("invalid_owner");
        };
        if handle.cancel.is_cancelled() {
            return fail("cancelled");
        }
        if handle.started.swap(true, Ordering::AcqRel) {
            return fail("invalid_session");
        }
        let Ok(size) = env.get_array_length(&model_path) else {
            return fail("invalid_model_path");
        };
        if size <= 0 || size > 4096 {
            return fail("invalid_model_path");
        }
        let Ok(bytes) = env.convert_byte_array(&model_path) else {
            return fail("invalid_model_path");
        };
        if bytes.contains(&0) {
            return fail("invalid_model_path");
        }
        let Ok(text) = String::from_utf8(bytes) else {
            return fail("invalid_model_path");
        };
        let choice = RecognizerChoice::WechatOffline {
            model_dir: PathBuf::from(text),
        };
        let decoded = match choice.start(SessionId(id as u64), handle.cancel.clone()) {
            Ok(session) => session,
            Err(error) => {
                return match error.to_string().as_str() {
                    "cancelled" => fail("cancelled"),
                    "voicekey_decoder_busy" => fail("voicekey_decoder_busy"),
                    _ => fail("invalid_voicekey_model"),
                }
            }
        };
        let Ok(mut slot) = handle.session.lock() else {
            return fail("voicekey_lock");
        };
        if handle.cancel.is_cancelled() {
            decoded.cancel();
            return fail("cancelled");
        }
        *slot = Some(Arc::new(decoded));
        json!({"ready":true,"engine_id":"voicekey-wechat"})
    })
}
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_accept(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    pcm: JShortArray,
    count: jint,
) -> jbyteArray {
    boundary(&mut env, |env| {
        let Some(handle) = owned(id) else {
            return fail("invalid_owner");
        };
        if handle.cancel.is_cancelled() {
            return fail("cancelled");
        }
        if handle.finished.load(Ordering::Acquire) {
            return fail("invalid_session");
        }
        if count <= 0 || count > crate::MAX_FRAME_SAMPLES as jint {
            return fail("invalid_audio");
        }
        let Ok(size) = env.get_array_length(&pcm) else {
            return fail("invalid_audio");
        };
        if count > size {
            return fail("invalid_audio");
        }
        let mut bytes = vec![0i16; count as usize];
        if env.get_short_array_region(&pcm, 0, &mut bytes).is_err() {
            return fail("invalid_audio");
        }
        let Some(session) = session(&handle) else {
            return fail("invalid_session");
        };
        if let Err(error) = session.push_wait(&bytes, Duration::from_secs(5)) {
            session.cancel();
            return fail(&error.to_string());
        }
        let partial = session
            .poll_partial()
            .filter(|x| x.session == SessionId(id as u64));
        if handle.cancel.is_cancelled() {
            return fail("cancelled");
        }
        let mut reply = json!({"partial_updated":partial.is_some(),"has_nonzero_pcm":session.has_nonzero_pcm(),"engine_id":"voicekey-wechat"});
        if let Some(partial) = partial {
            reply["text"] = json!(partial.text);
        }
        reply
    })
}
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_finish(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    timeout_ms: jint,
) -> jbyteArray {
    boundary(&mut env, |_| {
        let timeout = match crate::checked_finish_timeout_ms(timeout_ms) {
            Ok(timeout) => timeout,
            Err(_) => return fail("invalid_timeout"),
        };
        let Some(handle) = owned(id) else {
            return fail("invalid_owner");
        };
        if handle.cancel.is_cancelled() {
            return fail("cancelled");
        }
        if handle.finished.swap(true, Ordering::AcqRel) {
            return fail("invalid_session");
        }
        // No handle/session mutex is held during the real blocking wait, so
        // cancellation can always reach the same token while finalizing.
        let Some(session) = session(&handle) else {
            return fail("invalid_session");
        };
        let result = session.finish(timeout);
        if let Ok(mut slot) = handle.session.lock() {
            slot.take();
        }
        if handle.cancel.is_cancelled() {
            return fail("cancelled");
        }
        match result {
            Ok(text) => json!({"text":text,"engine_id":"voicekey-wechat"}),
            Err(error) => {
                if error.to_string() == "decode_timeout" {
                    fail("decode_timeout")
                } else {
                    fail("decode_failure")
                }
            }
        }
    })
}
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_cancel(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    let _ = std::panic::catch_unwind(|| {
        if let Some(handle) = find(id) {
            handle.cancel.cancel();
            if let Some(session) = session(&handle) {
                session.cancel();
            }
        }
    });
}
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_close(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    std::panic::catch_unwind(|| {
        let Some(handle) = owned(id) else {
            return JNI_FALSE;
        };
        handle.cancel.cancel();
        let done = session(&handle).is_none_or(|x| x.cancel_and_wait(Duration::from_secs(2)));
        if let Ok(mut slot) = handle.session.lock() {
            slot.take();
        }
        if let Ok(mut pool) = handles().lock() {
            pool.remove(&id);
        }
        crate::release_cache();
        // A timed-out worker retains the single physical decoder permit until
        // actual exit. false never means a detached calculation was killed.
        if done {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
    .unwrap_or(JNI_FALSE)
}
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_checkModel(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Ok(size) = env
            .call_method(&directory, "length", "()I", &[])
            .and_then(|x| x.i())
        else {
            return false;
        };
        if size <= 0 || size > 4096 {
            return false;
        }
        let Ok(text) = env.get_string(&directory) else {
            return false;
        };
        let path = PathBuf::from(text.to_string_lossy().into_owned());
        crate::validate_model(&path).is_ok()
    }))
    .unwrap_or(false);
    if env.exception_check().unwrap_or(true) {
        let _ = env.exception_clear();
        return JNI_FALSE;
    }
    if result {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeVoiceKey_releaseCache(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if std::panic::catch_unwind(crate::release_cache).unwrap_or(false) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
