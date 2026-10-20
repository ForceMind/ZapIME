// SPDX-License-Identifier: GPL-3.0-only
use uuid::Uuid;
use zapime_crypto::*;
fn binding() -> Binding {
    Binding {
        profile_id: Uuid::new_v4(),
        record_id: Uuid::new_v4(),
        operation_id: Uuid::new_v4(),
        key_id: Uuid::new_v4(),
        kind: 4,
    }
}
#[test]
fn ciphertext_is_bound_to_record_and_rejects_wrong_key() {
    let key = SecretKey::generate().unwrap();
    let original = binding();
    let text = "合成收藏内容".as_bytes();
    let encrypted = seal(&key, &original, text).unwrap();
    assert!(!String::from_utf8_lossy(&encrypted).contains("合成收藏内容"));
    assert_eq!(open(&key, &original, &encrypted).unwrap().as_slice(), text);
    let mut wrong = original.clone();
    wrong.record_id = Uuid::new_v4();
    assert!(open(&key, &wrong, &encrypted).is_err());
    assert!(open(&SecretKey::generate().unwrap(), &original, &encrypted).is_err());
    assert_ne!(seal(&key, &original, text).unwrap(), encrypted);
}
#[test]
fn lost_device_recovery_restores_key_and_data_into_fresh_database() {
    use zapime_data::{Content, Store};
    let dir = tempfile::tempdir().unwrap();
    let mut old = Store::open(&dir.path().join("old.db")).unwrap();
    old.edit(
        Uuid::new_v4(),
        Uuid::new_v4(),
        0,
        Uuid::new_v4(),
        Content::Favorite {
            title: "合成标题".into(),
            text: "离线恢复验证".into(),
        },
        false,
    )
    .unwrap();
    let key = SecretKey::generate().unwrap();
    let bind = binding();
    let ciphertext = seal(&key, &bind, b"before lost device").unwrap();
    let (code, package) = create_recovery(&key, &old.export_plaintext().unwrap()).unwrap();
    let saved_code = code.expose().to_owned();
    let expected = old.records(true).unwrap();
    drop(old);
    drop(key);
    drop(code);
    let code = RecoveryCode::parse(&saved_code).unwrap();
    let (restored_key, archive) = restore_recovery(&code, &package).unwrap();
    assert_eq!(
        open(&restored_key, &bind, &ciphertext).unwrap().as_slice(),
        b"before lost device"
    );
    let mut fresh = Store::open(&dir.path().join("fresh.db")).unwrap();
    fresh.restore_plaintext(&archive).unwrap();
    assert_eq!(fresh.records(true).unwrap(), expected);
}
#[test]
fn wrong_recovery_code_and_tampered_package_fail_closed() {
    let key = SecretKey::generate().unwrap();
    let (code, package) = create_recovery(&key, b"synthetic archive").unwrap();
    let (wrong, _) = create_recovery(&key, b"different").unwrap();
    assert!(restore_recovery(&wrong, &package).is_err());
    let mut json: serde_json::Value = serde_json::from_slice(&package).unwrap();
    json["version"] = 2.into();
    assert!(restore_recovery(&code, &serde_json::to_vec(&json).unwrap()).is_err());
    json["version"] = 1.into();
    let cipher = json["ciphertext"].as_str().unwrap();
    json["ciphertext"] = format!(
        "{}{}",
        if cipher.starts_with('A') { "B" } else { "A" },
        &cipher[1..]
    )
    .into();
    assert!(restore_recovery(&code, &serde_json::to_vec(&json).unwrap()).is_err());
}
