// SPDX-License-Identifier: GPL-3.0-only
//! Stateless, bounded sync-worker bridge. No input engine, HTTP or logging.
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jbyteArray, jlong, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::{
    fs,
    panic::{catch_unwind, AssertUnwindSafe},
    path::{Component, PathBuf},
};
use uuid::Uuid;
use zapime_data::{sync::UploadOperation, Store};
use zapime_sync::{
    client,
    workspace::{self, Material},
    Retention,
};
use zeroize::Zeroizing;

const MATERIAL_LIMIT: usize = 1024;
const RECOVERY_LIMIT: usize = 16 * 1024;

fn java_string(
    env: &mut JNIEnv<'_>,
    value: &JString<'_>,
    max: i32,
    error: &'static str,
) -> Result<String, String> {
    if value.is_null() {
        return Err(error.into());
    }
    // String.length is final and bounds the native UTF conversion before it
    // allocates. Recheck UTF-8 byte size below for non-ASCII path characters.
    let length = env
        .call_method(value, "length", "()I", &[])
        .and_then(|value| value.i())
        .map_err(|_| error)?;
    if length <= 0 || length > max {
        return Err(error.into());
    }
    let text: String = env.get_string(value).map_err(|_| error)?.into();
    if text.len() > max as usize {
        return Err(error.into());
    }
    Ok(text)
}

fn account(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<Uuid, String> {
    let text = java_string(env, value, 36, "sync_workspace_invalid")?;
    let id = Uuid::parse_str(&text).map_err(|_| "sync_workspace_invalid")?;
    if id.is_nil() || id.to_string() != text {
        return Err("sync_workspace_invalid".into());
    }
    Ok(id)
}

fn database(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<PathBuf, String> {
    database_path(java_string(env, value, 4096, "sync_workspace_unavailable")?)
}

fn database_path(text: String) -> Result<PathBuf, String> {
    let root = PathBuf::from(text);
    if root.as_os_str().len() > 4096
        || !root.is_absolute()
        || root.components().any(|c| c == Component::ParentDir)
    {
        return Err("sync_workspace_unavailable".into());
    }
    let canonical = match fs::symlink_metadata(&root) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            // Read-only preparation inspection must still report an absent
            // profile without creating the root or any database directories.
            return Ok(root.join("data/personal.sqlite3"));
        }
        Ok(metadata) if metadata.file_type().is_dir() => {
            fs::canonicalize(&root).map_err(|_| "sync_workspace_unavailable")?
        }
        _ => return Err("sync_workspace_unavailable".into()),
    };
    let data = canonical.join("data");
    match fs::symlink_metadata(&data) {
        Ok(metadata) if !metadata.file_type().is_dir() => {
            return Err("sync_workspace_unavailable".into());
        }
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => {
            return Err("sync_workspace_unavailable".into());
        }
        _ => {}
    }
    Ok(data.join("personal.sqlite3"))
}

fn bytes(
    env: &mut JNIEnv<'_>,
    value: &JByteArray<'_>,
    max: usize,
) -> Result<Zeroizing<Vec<u8>>, String> {
    if value.is_null() {
        return Err("sync_workspace_invalid".into());
    }
    let size = env
        .get_array_length(value)
        .map_err(|_| "sync_workspace_invalid")?;
    if size <= 0 || size as usize > max {
        return Err("sync_workspace_invalid".into());
    }
    env.convert_byte_array(value)
        .map(Zeroizing::new)
        .map_err(|_| "sync_workspace_invalid".into())
}

fn material(
    env: &mut JNIEnv<'_>,
    profile: Uuid,
    value: &JByteArray<'_>,
) -> Result<Material, String> {
    let wire = bytes(env, value, MATERIAL_LIMIT)?;
    Material::decode(profile, &wire).map_err(|e| e.to_string())
}

fn reply(env: &mut JNIEnv<'_>, result: Result<Value, String>) -> jstring {
    let body = result.unwrap_or_else(|error| json!({"error": error}));
    env.new_string(body.to_string())
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

fn sync_store(env: &mut JNIEnv<'_>, root: &JString<'_>) -> Result<Store, String> {
    Store::open_sync_writer(&database(env, root)?).map_err(|_| "sync_client_storage".into())
}

fn client_error(error: client::Error) -> Value {
    let code = error.to_string();
    match error {
        client::Error::Page(error) => {
            let cause = error.cause.to_string();
            let (retention, retained_id, retention_error) = match error.retention {
                Retention::Saved(summary) => ("saved", Some(summary.id), None),
                Retention::NotEligible => ("not_eligible", None, None),
                Retention::Failed(error) => ("failed", None, Some(error.to_string())),
            };
            json!({"error":code,"cause":cause,"retention":retention,
                   "retained_id":retained_id,"retention_error":retention_error})
        }
        _ => json!({"error":code}),
    }
}

/// Each invocation opens/drops its own existing-v2 connection. Returned wire
/// comes from the frozen row, never a Java reconstruction or current record.
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_stageSyncUpload(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    profile: JString,
    wire: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let profile = account(&mut env, &profile)?;
        let material = material(&mut env, profile, &wire)?;
        let mut store = sync_store(&mut env, &root)?;
        let upload = client::stage_next(&mut store, profile, &material)
            .map_err(|error| error.to_string())?;
        match upload {
            None => Ok(json!({"pending":false})),
            Some(operation) => {
                let frozen = client::frozen_wire(&mut store, profile, &material, &operation)
                    .map_err(|error| error.to_string())?;
                if frozen.len() > client::MAX_ACK_BYTES {
                    return Err("sync_client_storage".into());
                }
                let frozen = String::from_utf8(frozen).map_err(|_| "sync_client_storage")?;
                Ok(json!({"pending":true,"wire":frozen}))
            }
        }
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_ackSyncUpload(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    profile: JString,
    device: JString,
    wire: JByteArray,
    expected: JByteArray,
    response: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let profile = account(&mut env, &profile)?;
        let device = account(&mut env, &device)?;
        let material = material(&mut env, profile, &wire)?;
        let expected = bytes(&mut env, &expected, client::MAX_ACK_BYTES)?;
        let expected: UploadOperation =
            serde_json::from_slice(&expected).map_err(|_| "sync_client_ack_invalid")?;
        let response = bytes(&mut env, &response, client::MAX_ACK_BYTES)?;
        let mut store = sync_store(&mut env, &root)?;
        client::acknowledge(&mut store, profile, device, &material, &expected, &response)
            .map_err(|error| error.to_string())?;
        Ok(json!({"acknowledged":true}))
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_syncPullCursor(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    profile: JString,
    wire: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let profile = account(&mut env, &profile)?;
        let material = material(&mut env, profile, &wire)?;
        let mut store = sync_store(&mut env, &root)?;
        let cursor = client::pull_cursor(&mut store, profile, &material)
            .map_err(|error| error.to_string())?;
        Ok(json!({"cursor":cursor}))
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_applySyncPage(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    profile: JString,
    wire: JByteArray,
    after: jlong,
    response: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if after < 0 {
            return Err("sync_workspace_invalid".into());
        }
        let profile = account(&mut env, &profile)?;
        let material = material(&mut env, profile, &wire)?;
        let response = bytes(&mut env, &response, zapime_data::quarantine::MAX_PAGE_BYTES)?;
        // The identical immutable bytes are strictly parsed before application;
        // a head is returned only when the existing full authentication and
        // transaction boundary accepts that same Page. Head is not a freshness
        // proof or a client cursor advancement instruction.
        let parsed_head =
            serde_json::from_slice::<zapime_sync::Page>(&response).map(|page| page.head);
        let mut store = sync_store(&mut env, &root)?;
        match client::apply_page(&mut store, profile, &material, after as u64, &response) {
            Ok(report) => {
                let head = parsed_head.map_err(|_| "sync_client_page_rejected")?;
                Ok(json!({"cursor":report.cursor,"applied":report.applied,
                    "echoed":report.echoed,"unchanged":report.unchanged,"head":head}))
            }
            Err(error) => Ok(client_error(error)),
        }
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_newSyncMaterial(
    mut env: JNIEnv,
    _: JClass,
    profile: JString,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let profile = account(&mut env, &profile).ok()?;
        let material = Material::generate(profile).ok()?;
        env.byte_array_from_slice(&material.encode())
            .ok()
            .map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_syncMaterialInfo(
    mut env: JNIEnv,
    _: JClass,
    profile: JString,
    wire: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let profile = account(&mut env, &profile)?;
        let material = material(&mut env, profile, &wire)?;
        Ok(json!({"account_id": material.profile, "key_id": material.key_id}))
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

/// Public identity only; the root and protected material never enter JSON.
#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_syncKeyDescriptor(
    mut env: JNIEnv,
    _: JClass,
    profile: JString,
    wire: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let profile = account(&mut env, &profile)?;
        let material = material(&mut env, profile, &wire)?;
        serde_json::to_value(material.descriptor()).map_err(|_| "sync_workspace_failed".into())
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

fn status(
    env: &mut JNIEnv<'_>,
    root: &JString<'_>,
    profile: &JString<'_>,
    wire: &JByteArray<'_>,
    prepare: bool,
) -> Result<Value, String> {
    let profile = account(env, profile)?;
    let path = database(env, root)?;
    let material = if wire.is_null() {
        None
    } else {
        Some(material(env, profile, wire)?)
    };
    let state = if prepare {
        workspace::prepare(
            &path,
            profile,
            material.as_ref().ok_or("sync_workspace_invalid")?,
        )
    } else {
        workspace::inspect(&path, profile, material.as_ref())
    }
    .map_err(|e| e.to_string())?;
    serde_json::to_value(state).map_err(|_| "sync_workspace_failed".into())
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_syncWorkspaceStatus(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    profile: JString,
    wire: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        status(&mut env, &root, &profile, &wire, false)
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_prepareSync(
    mut env: JNIEnv,
    _: JClass,
    root: JString,
    profile: JString,
    wire: JByteArray,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        status(&mut env, &root, &profile, &wire, true)
    }))
    .unwrap_or_else(|_| Err("sync_workspace_failed".into()));
    reply(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_createSyncRecovery(
    mut env: JNIEnv,
    _: JClass,
    profile: JString,
    wire: JByteArray,
    code_out: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        if code_out.is_null() || env.get_array_length(&code_out).ok()? != 43 {
            return None;
        }
        let profile = account(&mut env, &profile).ok()?;
        let material = material(&mut env, profile, &wire).ok()?;
        let (code, package) = workspace::create_recovery(&material).ok()?;
        if package.is_empty() || package.len() > RECOVERY_LIMIT {
            return None;
        }
        let result = env.byte_array_from_slice(&package).ok()?;
        let code = Zeroizing::new(code.expose().bytes().map(|b| b as i8).collect::<Vec<_>>());
        env.set_byte_array_region(&code_out, 0, &code).ok()?;
        Some(result.into_raw())
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_zapime_android_NativeCore_restoreSyncRecovery(
    mut env: JNIEnv,
    _: JClass,
    profile: JString,
    code: JByteArray,
    encrypted: JByteArray,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        if code.is_null() || env.get_array_length(&code).ok()? != 43 {
            return None;
        }
        let profile = account(&mut env, &profile).ok()?;
        let code = bytes(&mut env, &code, 43).ok()?;
        let encrypted = bytes(&mut env, &encrypted, RECOVERY_LIMIT).ok()?;
        let material = workspace::restore_recovery(profile, &code, &encrypted).ok()?;
        env.byte_array_from_slice(&material.encode())
            .ok()
            .map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn database_path_is_canonical_for_existing_roots_and_does_not_create_absent_profiles() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("profile");
        let absent = database_path(root.to_string_lossy().into_owned()).unwrap();
        assert_eq!(absent, root.join("data/personal.sqlite3"));
        assert!(!root.exists());
        fs::create_dir_all(root.join("data")).unwrap();
        assert_eq!(
            database_path(root.to_string_lossy().into_owned()).unwrap(),
            fs::canonicalize(&root)
                .unwrap()
                .join("data/personal.sqlite3")
        );
        assert!(database_path("relative-profile".into()).is_err());
        assert!(database_path(root.join("../profile").to_string_lossy().into_owned()).is_err());
        assert!(database_path("/".repeat(4097)).is_err());
        let file = dir.path().join("file-root");
        fs::write(&file, b"synthetic").unwrap();
        assert!(database_path(file.to_string_lossy().into_owned()).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn database_path_rejects_symlink_roots_and_data_directories() {
        use std::os::unix::fs::symlink;
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("root");
        let other = dir.path().join("other");
        fs::create_dir_all(&root).unwrap();
        fs::create_dir_all(&other).unwrap();
        let alias = dir.path().join("alias");
        symlink(&root, &alias).unwrap();
        assert!(database_path(alias.to_string_lossy().into_owned()).is_err());
        symlink(&other, root.join("data")).unwrap();
        assert!(database_path(root.to_string_lossy().into_owned()).is_err());
        assert!(!other.join("personal.sqlite3").exists());
    }

    #[test]
    fn native_failure_json_distinguishes_saved_not_eligible_and_failed_retention() {
        let saved = zapime_data::quarantine::RetainedSummary {
            id: "a".repeat(64),
            sequence: 1,
            after_cursor: 0,
            reason: zapime_data::quarantine::FailureKind::MissingKey,
            bytes: 128,
        };
        for (retention, expected) in [
            (Retention::Saved(saved), "saved"),
            (Retention::NotEligible, "not_eligible"),
            (
                Retention::Failed(zapime_data::Error::RetainedFull),
                "failed",
            ),
        ] {
            let value = client_error(client::Error::Page(zapime_sync::PreservedError {
                cause: Box::new(zapime_sync::Error::KeyUnavailable),
                retention,
            }));
            assert_eq!(value.as_object().unwrap().len(), 5);
            assert_eq!(value["error"], "sync_client_page_rejected");
            assert_eq!(value["cause"], "sync_key_unavailable");
            assert_eq!(value["retention"], expected);
            assert_eq!(value["retained_id"].is_string(), expected == "saved");
            assert_eq!(value["retention_error"].is_string(), expected == "failed");
        }
        let simple = client_error(client::Error::Ciphertext);
        assert_eq!(simple.as_object().unwrap().len(), 1);
        assert_eq!(simple["error"], "sync_client_ciphertext_invalid");
    }
}
