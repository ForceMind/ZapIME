// SPDX-License-Identifier: GPL-3.0-only
use serde_json::{json, Value};
use zapime_core::ffi::*;

fn resources() -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    let resources = dir.path().join("resources");
    std::fs::create_dir(&resources).unwrap();
    let db = rusqlite::Connection::open(resources.join("msime-pinyin.db")).unwrap();
    db.execute_batch("CREATE TABLE tbl_2_n(key TEXT,jp TEXT,value TEXT,weight INTEGER); INSERT INTO tbl_2_n VALUES('ni''hao','nh','你好',200);").unwrap();
    msime_engine::ensure_english_schema(&resources.join("msime-english.db")).unwrap();
    dir
}

fn open(dir: &std::path::Path) -> u64 {
    let config = json!({"resources":dir.join("resources"),"data":dir.join("data"),"cache":dir.join("cache"),"private":true}).to_string();
    unsafe { zapime_open(config.as_ptr(), config.len()) }
}

fn request(handle: u64, request: Value) -> Value {
    let bytes = request.to_string();
    unsafe {
        let buffer = zapime_request(handle, bytes.as_ptr(), bytes.len());
        let value =
            serde_json::from_slice(std::slice::from_raw_parts(buffer.data, buffer.len)).unwrap();
        zapime_buffer_free(buffer);
        value
    }
}

#[test]
fn native_buffer_roundtrip_and_session_lifetime() {
    assert_eq!(zapime_abi_version(), 1);
    let dir = resources();
    let handle = open(dir.path());
    assert_ne!(handle, 0);
    let mut view = json!(null);
    for c in "nihao".chars() {
        view = request(handle, json!({"op":"key","text":c.to_string()}));
    }
    assert_eq!(view["candidates"][0], "你好");
    assert_eq!(
        request(
            handle,
            json!({"op":"select","index":0,"generation":view["generation"]})
        )["commit"],
        "你好"
    );
    assert!(zapime_close(handle));
    assert!(!zapime_close(handle));
    assert_eq!(
        request(handle, json!({"op":"view"}))["error"],
        "invalid_handle"
    );
}

#[test]
fn malformed_and_bounded_inputs_are_rejected_before_read() {
    unsafe {
        assert_eq!(zapime_open(std::ptr::null(), 1), 0);
        assert_eq!(zapime_open(b"{}".as_ptr(), 2), 0);
        let oversized = zapime_request(0, b"x".as_ptr(), 35 * 1024 * 1024);
        let value: Value =
            serde_json::from_slice(std::slice::from_raw_parts(oversized.data, oversized.len))
                .unwrap();
        zapime_buffer_free(oversized);
        assert_eq!(value["error"], "invalid_json");
    }
}

#[test]
fn foreign_thread_cannot_access_or_close_a_session() {
    let dir = resources();
    let handle = open(dir.path());
    std::thread::spawn(move || {
        let other_dir = resources();
        let other = open(other_dir.path());
        assert_ne!(other, handle);
        assert_eq!(
            request(handle, json!({"op":"view"}))["error"],
            "invalid_handle"
        );
        assert!(!zapime_close(handle));
        assert!(zapime_close(other));
    })
    .join()
    .unwrap();
    assert!(request(handle, json!({"op":"view"})).get("error").is_none());
    assert!(zapime_close(handle));
}
