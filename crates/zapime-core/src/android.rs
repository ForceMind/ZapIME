// SPDX-License-Identifier: GPL-3.0-only
use super::InputSession;
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jbyteArray, jlong, jstring},
    JNIEnv,
};
use std::{
    cell::RefCell,
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    path::Path,
};

// 句柄只在创建它的线程上有效，避免把非线程安全输入会话跨线程传递。
thread_local! {
    static SESSIONS: RefCell<(i64, HashMap<i64, InputSession>)> = RefCell::new((1, HashMap::new()));
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_createLocalBackup(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    code_out: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        if env.get_array_length(&code_out).ok()? != 43 {
            return None;
        }
        let root: String = env.get_string(&root).ok()?.into();
        if root.len() > 4096 {
            return None;
        }
        let backup = crate::local_backup::create(Path::new(&root)).ok()?;
        // Allocate the encrypted result before exposing the recovery code.
        let result = env.byte_array_from_slice(&backup.package).ok()?;
        let code =
            zeroize::Zeroizing::new(backup.code.iter().map(|b| *b as i8).collect::<Vec<_>>());
        env.set_byte_array_region(&code_out, 0, &code).ok()?;
        Some(result.into_raw())
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_restoreLocalBackup(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    code: JByteArray,
    encrypted: JByteArray,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let code_len = env
            .get_array_length(&code)
            .map_err(|_| "invalid_or_unauthenticated_backup")?;
        let size = env
            .get_array_length(&encrypted)
            .map_err(|_| "invalid_or_unauthenticated_backup")?;
        if code_len != 43 || size <= 0 || size as usize > crate::local_backup::MAX_PACKAGE {
            return Err("invalid_or_unauthenticated_backup");
        }
        let root: String = env
            .get_string(&root)
            .map_err(|_| "local_profile_unavailable")?
            .into();
        if root.len() > 4096 {
            return Err("local_profile_unavailable");
        }
        let code = zeroize::Zeroizing::new(
            env.convert_byte_array(&code)
                .map_err(|_| "local_backup_failed")?,
        );
        let encrypted = env
            .convert_byte_array(&encrypted)
            .map_err(|_| "local_backup_failed")?;
        crate::local_backup::restore(Path::new(&root), &code, &encrypted)
    }))
    .unwrap_or(Err("local_backup_failed"));
    let reply = match outcome {
        Ok(count) => serde_json::json!({"restored_records":count}),
        Err(code) => serde_json::json!({"error":code}),
    };
    env.new_string(reply.to_string())
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// Independent sync-worker read; does not allocate an input session or writer.
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_syncProfile(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
) -> jstring {
    let response = catch_unwind(AssertUnwindSafe(|| {
        let root: String = env.get_string(&root).map_err(|_| ())?.into();
        if root.len() > 4096 || !Path::new(&root).is_absolute() {
            return Err(());
        }
        let status =
            zapime_data::sync::inspect_profile(&Path::new(&root).join("data/personal.sqlite3"))
                .map_err(|_| ())?;
        serde_json::to_string(&status).map_err(|_| ())
    }))
    .ok()
    .and_then(Result::<String, ()>::ok)
    .unwrap_or_else(|| "{\"error\":\"sync_profile_read_failed\"}".into());
    env.new_string(response)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_open(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    private: jboolean,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let root: String = env.get_string(&root).ok()?.into();
        let root = Path::new(&root);
        let session = InputSession::open(
            &root.join("resources"),
            &root.join("data"),
            &root.join("cache"),
            private != 0,
        )
        .ok()?;
        Some(SESSIONS.with(|sessions| {
            let mut sessions = sessions.borrow_mut();
            let id = sessions.0;
            sessions.0 += 1;
            sessions.1.insert(id, session);
            id
        }))
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_request(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    input: JString,
) -> jstring {
    let response = catch_unwind(AssertUnwindSafe(|| {
        let input: String = env.get_string(&input).map_err(|_| ())?.into();
        if input.len() > 34 * 1024 * 1024 {
            return Err(());
        }
        let value = serde_json::from_str(&input).map_err(|_| ())?;
        SESSIONS.with(|sessions| {
            sessions
                .borrow_mut()
                .1
                .get_mut(&handle)
                .map(|session| session.request(&value).to_string())
                .ok_or(())
        })
    }))
    .ok()
    .and_then(Result::ok)
    .unwrap_or_else(|| "{\"error\":\"native_request_failed\"}".into());
    env.new_string(response)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_close(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        SESSIONS.with(|sessions| {
            sessions.borrow_mut().1.remove(&handle);
        })
    }));
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_queryPersonal(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    input: JString,
) -> jstring {
    let response = catch_unwind(AssertUnwindSafe(|| {
        let root: String = env.get_string(&root).map_err(|_| ())?.into();
        let input: String = env.get_string(&input).map_err(|_| ())?.into();
        if input.len() > 4096 {
            return Err(());
        }
        let request: serde_json::Value = serde_json::from_str(&input).map_err(|_| ())?;
        if request["op"] != "personal_query" {
            return Err(());
        }
        let mut store =
            zapime_data::Store::open_panel_reader(&Path::new(&root).join("data/personal.sqlite3"))
                .map_err(|_| ())?;
        Ok(super::personal::query_store(&mut store, &request).to_string())
    }))
    .ok()
    .and_then(Result::<String, ()>::ok)
    .unwrap_or_else(|| "{\"error\":\"personal_query_failed\"}".into());
    env.new_string(response)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}
