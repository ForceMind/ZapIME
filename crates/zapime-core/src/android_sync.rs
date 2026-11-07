// SPDX-License-Identifier: GPL-3.0-only
//! Stateless, bounded sync-worker bridge. No input engine, HTTP or logging.
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jbyteArray, jstring},
    JNIEnv,
};
use serde_json::{json, Value};
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    path::{Component, PathBuf},
};
use uuid::Uuid;
use zapime_sync::workspace::{self, Material};
use zeroize::Zeroizing;

const MATERIAL_LIMIT: usize = 1024;
const RECOVERY_LIMIT: usize = 16 * 1024;

fn account(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<Uuid, String> {
    let text: String = env
        .get_string(value)
        .map_err(|_| "sync_workspace_invalid")?
        .into();
    let id = Uuid::parse_str(&text).map_err(|_| "sync_workspace_invalid")?;
    if id.is_nil() || id.to_string() != text {
        return Err("sync_workspace_invalid".into());
    }
    Ok(id)
}

fn database(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<PathBuf, String> {
    let text: String = env
        .get_string(value)
        .map_err(|_| "sync_workspace_unavailable")?
        .into();
    let root = PathBuf::from(text);
    if root.as_os_str().len() > 4096
        || !root.is_absolute()
        || root.components().any(|c| c == Component::ParentDir)
    {
        return Err("sync_workspace_unavailable".into());
    }
    Ok(root.join("data/personal.sqlite3"))
}

fn bytes(
    env: &mut JNIEnv<'_>,
    value: &JByteArray<'_>,
    max: usize,
) -> Result<Zeroizing<Vec<u8>>, String> {
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
        if env.get_array_length(&code_out).ok()? != 43 {
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
        if env.get_array_length(&code).ok()? != 43 {
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
