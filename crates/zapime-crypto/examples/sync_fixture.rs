// SPDX-License-Identifier: GPL-3.0-only
//! 仅供合成灾备验收：恢复材料写入私密临时文件，绝不输出到日志或服务器。
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{fs, io::Write, path::Path};
use uuid::Uuid;
use zapime_crypto::{
    create_recovery, open, restore_recovery, seal, Binding, RecoveryCode, SecretKey,
};
use zapime_data::{Content, Store};
use zeroize::Zeroizing;

fn write_private(path: &Path, value: &Value) {
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path).expect("private fixture destination");
    file.write_all(&serde_json::to_vec(value).unwrap()).unwrap();
}

fn main() {
    let args: Vec<_> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("seal") if args.len() == 5 => {
            let profile = Uuid::parse_str(&args[2]).unwrap();
            let dir = tempfile::tempdir().unwrap();
            let mut store = Store::open(&dir.path().join("source.sqlite3")).unwrap();
            let device = store.device_id().unwrap();
            let record = Uuid::new_v4();
            store.edit(Uuid::new_v4(), record, 0, device, Content::Favorite {
                title: "合成灾备验证".into(), text: "永久收藏恢复探针".into()
            }, false).unwrap();
            let payload = serde_json::to_vec(&store.records(false).unwrap()[0]).unwrap();
            let key = SecretKey::generate().unwrap();
            let binding = Binding { profile_id: profile, record_id: record, operation_id: Uuid::new_v4(), key_id: Uuid::new_v4(), kind: 4 };
            let envelope: Value = serde_json::from_slice(&seal(&key, &binding, &payload).unwrap()).unwrap();
            let (code, recovery) = create_recovery(&key, &store.export_plaintext().unwrap()).unwrap();
            let operation = json!({"operation_id":binding.operation_id,"record_id":record,"key_id":binding.key_id,"kind":4,"base_revision":0,"deleted":false,"envelope":envelope});
            write_private(Path::new(&args[3]), &json!({"recovery_code":code.expose(),"recovery_package":serde_json::from_slice::<Value>(&recovery).unwrap(),"operation":operation}));
            // 单独输出给网络脚本的文件只包含密文，不能上传整个恢复 fixture。
            write_private(Path::new(&args[4]), &operation);
            println!("PASS: synthetic ciphertext sealed; recovery material kept outside server");
        }
        Some("descriptor") if args.len() == 5 => {
            let profile = Uuid::parse_str(&args[2]).unwrap();
            let fixture: Value = serde_json::from_slice(&fs::read(&args[3]).unwrap()).unwrap();
            let code = RecoveryCode::parse(fixture["recovery_code"].as_str().unwrap()).unwrap();
            let (key, _archive) = restore_recovery(
                &code,
                &serde_json::to_vec(&fixture["recovery_package"]).unwrap(),
            )
            .unwrap();
            let op = &fixture["operation"];
            let binding = Binding {
                profile_id: profile,
                record_id: Uuid::parse_str(op["record_id"].as_str().unwrap()).unwrap(),
                operation_id: Uuid::parse_str(op["operation_id"].as_str().unwrap()).unwrap(),
                key_id: Uuid::parse_str(op["key_id"].as_str().unwrap()).unwrap(),
                kind: u8::try_from(op["kind"].as_u64().unwrap()).unwrap(),
            };
            // Authenticate the supplied account against the real fixture AEAD
            // before publishing any descriptor. The network never sees root.
            let plaintext = open(
                &key,
                &binding,
                &serde_json::to_vec(&op["envelope"]).unwrap(),
            )
            .unwrap();
            let record: zapime_data::Record = serde_json::from_slice(&plaintext).unwrap();
            assert_eq!(record.id, binding.record_id);
            assert_eq!(record.content.kind(), binding.kind);
            assert_eq!(Some(record.deleted), op["deleted"].as_bool());
            // Version-1 protected-material framing is independently fixed by
            // workspace::Material (72 bytes). This probe needs no sync crate
            // dependency and does not print or serialize these private bytes.
            let mut material = Zeroizing::new(Vec::with_capacity(72));
            material.extend_from_slice(b"ZSKM\0\0\0\x01");
            material.extend_from_slice(profile.as_bytes());
            material.extend_from_slice(binding.key_id.as_bytes());
            material.extend_from_slice(key.to_protected_storage().as_ref());
            assert_eq!(material.len(), 72);
            let fingerprint = Sha256::digest(material.as_slice())
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>();
            write_private(
                Path::new(&args[4]),
                &json!({"version":1,"key_id":binding.key_id,"material_sha256":fingerprint}),
            );
            println!("PASS: synthetic public key descriptor authenticated; root stays private");
        }
        Some("verify") if args.len() == 4 => {
            let fixture: Value = serde_json::from_slice(&fs::read(&args[2]).unwrap()).unwrap();
            let change: Value = serde_json::from_slice(&fs::read(&args[3]).unwrap()).unwrap();
            let code = RecoveryCode::parse(fixture["recovery_code"].as_str().unwrap()).unwrap();
            let (key, archive) = restore_recovery(&code, &serde_json::to_vec(&fixture["recovery_package"]).unwrap()).unwrap();
            let op = &change["operation"];
            let binding = Binding { profile_id: Uuid::parse_str(change["account_id"].as_str().unwrap()).unwrap(),
                record_id: Uuid::parse_str(op["record_id"].as_str().unwrap()).unwrap(),
                operation_id: Uuid::parse_str(op["operation_id"].as_str().unwrap()).unwrap(),
                key_id: Uuid::parse_str(op["key_id"].as_str().unwrap()).unwrap(), kind: u8::try_from(op["kind"].as_u64().unwrap()).unwrap() };
            let plaintext = open(&key, &binding, &serde_json::to_vec(&op["envelope"]).unwrap()).unwrap();
            let record: zapime_data::Record = serde_json::from_slice(&plaintext).unwrap();
            assert_eq!(record.id, binding.record_id);
            assert_eq!(op["deleted"].as_bool(), Some(record.deleted));
            let dir = tempfile::tempdir().unwrap();
            let mut restored = Store::open(&dir.path().join("fresh.sqlite3")).unwrap();
            restored.restore_plaintext(&archive).unwrap();
            assert_eq!(restored.records(false).unwrap(), vec![record]);
            println!("PASS: fresh process restored key/archive and authenticated server ciphertext");
        }
        _ => panic!("usage: sync_fixture seal account-id private-fixture ciphertext-file | descriptor account-id private-fixture public-descriptor-file | verify private-fixture change-file"),
    }
}
