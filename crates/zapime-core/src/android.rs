// SPDX-License-Identifier: GPL-3.0-only
use super::InputSession;
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jlong, jstring},
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
