// SPDX-License-Identifier: GPL-3.0-only
//! 原生宿主的版本化 C ABI。输入缓冲由调用方持有，输出必须且只能释放一次。
use crate::InputSession;
use serde_json::{json, Value};
use std::{
    cell::RefCell,
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    path::Path,
    sync::atomic::{AtomicU64, Ordering},
};

const MAX_REQUEST: usize = 34 * 1024 * 1024;
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
thread_local! {
    static SESSIONS: RefCell<HashMap<u64, InputSession>> = RefCell::new(HashMap::new());
}

#[repr(C)]
pub struct ZapBuffer {
    pub data: *mut u8,
    pub len: usize,
}

fn output(value: Value) -> ZapBuffer {
    let bytes = value.to_string().into_bytes().into_boxed_slice();
    let len = bytes.len();
    ZapBuffer {
        data: Box::into_raw(bytes).cast::<u8>(),
        len,
    }
}

// 长度先校验，禁止在原生边界使用无界 C 字符串扫描。
unsafe fn decode(data: *const u8, len: usize) -> Result<Value, ()> {
    if data.is_null() || len == 0 || len > MAX_REQUEST {
        return Err(());
    }
    // SAFETY: 调用方按 ABI 契约保证非空指针在 len 字节范围内可读。
    serde_json::from_slice(unsafe { std::slice::from_raw_parts(data, len) }).map_err(|_| ())
}

#[no_mangle]
pub extern "C" fn zapime_abi_version() -> u32 {
    1
}

/// 配置 JSON 包含 resources/data/cache 绝对路径与 private 布尔值；错误返回 0。
/// # Safety
/// data 必须在本次调用期间指向 len 字节可读内存，或为空以返回错误。
#[no_mangle]
pub unsafe extern "C" fn zapime_open(data: *const u8, len: usize) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: 由公开 ABI 的指针契约保证。
        let config = unsafe { decode(data, len) }.ok()?;
        let path = |key| {
            config[key]
                .as_str()
                .map(Path::new)
                .filter(|p| p.is_absolute())
        };
        let session = InputSession::open(
            path("resources")?,
            path("data")?,
            path("cache")?,
            config["private"].as_bool()?,
        )
        .ok()?;
        // 进程全局唯一 ID：其他线程恰好有相同本地序号时，也不能误取会话。
        let id = NEXT_HANDLE
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
            .ok()?;
        SESSIONS.with(|sessions| sessions.borrow_mut().insert(id, session));
        Some(id)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

/// 所有会话操作必须在创建线程串行执行。错误以 JSON 返回，不穿越 ABI 展开栈。
/// # Safety
/// data 必须在本次调用期间指向 len 字节可读内存，或为空以返回错误。
#[no_mangle]
pub unsafe extern "C" fn zapime_request(handle: u64, data: *const u8, len: usize) -> ZapBuffer {
    let value = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: 由公开 ABI 的指针契约保证。
        let request = unsafe { decode(data, len) }.map_err(|_| "invalid_json")?;
        SESSIONS.with(|sessions| {
            sessions
                .borrow_mut()
                .get_mut(&handle)
                .map(|session| session.request(&request))
                .ok_or("invalid_handle")
        })
    }));
    output(match value {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"error":error}),
        Err(_) => json!({"error":"native_panic"}),
    })
}

#[no_mangle]
pub extern "C" fn zapime_close(handle: u64) -> bool {
    catch_unwind(AssertUnwindSafe(|| {
        SESSIONS.with(|sessions| sessions.borrow_mut().remove(&handle).is_some())
    }))
    .unwrap_or(false)
}

/// # Safety
/// buffer 必须是本库返回且未释放的原始输出；不得改动 data/len，不得重复释放。
#[no_mangle]
pub unsafe extern "C" fn zapime_buffer_free(buffer: ZapBuffer) {
    if !buffer.data.is_null() {
        // SAFETY: Box 分配和长度来自 output，所有权由调用方交回。
        drop(unsafe { Box::from_raw(std::ptr::slice_from_raw_parts_mut(buffer.data, buffer.len)) });
    }
}
