// SPDX-License-Identifier: GPL-3.0-only
use serde_json::json;
use std::path::Path;
fn main() {
    let root = std::env::args().nth(1).expect("resource directory");
    let dir = std::env::temp_dir().join(format!("zapime-probe-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut session = zapime_core::InputSession::open(
        Path::new(&root),
        &dir.join("data"),
        &dir.join("cache"),
        true,
    )
    .unwrap();
    for (input, expected) in [
        ("nihao", "你好"),
        ("zhongguo", "中国"),
        ("shurufa", "输入法"),
    ] {
        session.request(&json!({"op":"reset"}));
        let mut view = json!(null);
        for c in input.chars() {
            view = session.request(&json!({"op":"key","text":c.to_string()}));
        }
        assert!(
            view["candidates"]
                .as_array()
                .unwrap()
                .contains(&json!(expected)),
            "expected synthetic probe candidate"
        );
        println!("PASS {} -> {}", input, expected);
    }
    drop(session);
    msime_engine::close_cached_databases();
    std::fs::remove_dir_all(dir).unwrap();
}
