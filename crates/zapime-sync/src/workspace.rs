// SPDX-License-Identifier: GPL-3.0-only
//! Explicit, local-only key preparation. No HTTP, remote key registration,
//! pairing, records export, consent flag or automatic upload is implemented here.
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{fs, path::Path};
use uuid::Uuid;
use zapime_crypto::{RecoveryCode, SecretKey};
use zapime_data::{sync::WorkspaceBinding, Store};
use zeroize::Zeroizing;

const MAGIC: &[u8; 8] = b"ZSKM\0\0\0\x01";
const RECOVERY_DOMAIN: &[u8] = b"ZapIME/sync-material/v1\0";
pub const MATERIAL_BYTES: usize = 72;
pub const MAX_RECOVERY_PACKAGE: usize = 16 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("sync_material_invalid")]
    Invalid,
    #[error("sync_material_account_mismatch")]
    Account,
    #[error("sync_workspace_unavailable")]
    Unavailable,
    #[error("sync_workspace_binding_mismatch")]
    Binding,
    #[error("sync_material_recovery_invalid")]
    Recovery,
    #[error("sync_material_generation_failed")]
    Generation,
}

/// Vault plaintext only. Deliberately no Debug, Display, Clone or Serialize.
/// Account/key UUIDs are public metadata; the secret stays private and zeroizes.
pub struct Material {
    pub profile: Uuid,
    pub key_id: Uuid,
    key: SecretKey,
}
impl Material {
    /// User-invoked local candidate generation only. This does not establish
    /// an account-wide key or infer that an empty server has no existing key.
    pub fn generate(profile: Uuid) -> Result<Self, Error> {
        if profile.is_nil() {
            return Err(Error::Invalid);
        }
        Ok(Self {
            profile,
            key_id: Uuid::new_v4(),
            key: SecretKey::generate().map_err(|_| Error::Generation)?,
        })
    }
    pub fn decode(profile: Uuid, bytes: &[u8]) -> Result<Self, Error> {
        if profile.is_nil() || bytes.len() != MATERIAL_BYTES || &bytes[..8] != MAGIC {
            return Err(Error::Invalid);
        }
        let account = Uuid::from_slice(&bytes[8..24]).map_err(|_| Error::Invalid)?;
        let key_id = Uuid::from_slice(&bytes[24..40]).map_err(|_| Error::Invalid)?;
        if account.is_nil() || key_id.is_nil() {
            return Err(Error::Invalid);
        }
        if account != profile {
            return Err(Error::Account);
        }
        Ok(Self {
            profile,
            key_id,
            key: SecretKey::from_protected_storage(&bytes[40..]).map_err(|_| Error::Invalid)?,
        })
    }
    /// Only pass this buffer directly to protected platform storage or the
    /// matching private native call. Never JSON/stringify/log/export it.
    pub fn encode(&self) -> Zeroizing<Vec<u8>> {
        let mut bytes = Zeroizing::new(Vec::with_capacity(MATERIAL_BYTES));
        bytes.extend_from_slice(MAGIC);
        bytes.extend_from_slice(self.profile.as_bytes());
        bytes.extend_from_slice(self.key_id.as_bytes());
        bytes.extend_from_slice(self.key.to_protected_storage().as_ref());
        bytes
    }
    fn validate(&self, profile: Uuid) -> Result<(), Error> {
        if profile.is_nil() || self.profile.is_nil() || self.key_id.is_nil() {
            return Err(Error::Invalid);
        }
        if self.profile != profile {
            return Err(Error::Account);
        }
        Ok(())
    }
    fn binding(&self) -> WorkspaceBinding {
        WorkspaceBinding {
            profile_id: self.profile,
            key_id: self.key_id,
            material_sha256: Sha256::digest(self.encode().as_slice()).into(),
        }
    }
}

/// Fixed, non-secret UI response. Prepared means local vault material agrees
/// with this database, not remotely registered, synchronized or recoverable data.
#[derive(Debug, PartialEq, Eq, Serialize)]
pub struct Status {
    pub initialized: bool,
    pub account_id: Option<Uuid>,
    pub key_id: Option<Uuid>,
    pub material_present: bool,
    pub prepared: bool,
}
fn existing(path: &Path) -> Result<bool, Error> {
    if !path.is_absolute() {
        return Err(Error::Unavailable);
    }
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Ok(metadata) if metadata.file_type().is_file() => Ok(true),
        _ => Err(Error::Unavailable),
    }
}
fn storage(error: zapime_data::Error) -> Error {
    match error {
        zapime_data::Error::SyncWorkspace | zapime_data::Error::SyncProfile => Error::Binding,
        _ => Error::Unavailable,
    }
}
fn status(binding: Option<WorkspaceBinding>, material: Option<&Material>) -> Result<Status, Error> {
    if let (Some(bound), Some(material)) = (&binding, material) {
        if *bound != material.binding() {
            return Err(Error::Binding);
        }
    }
    Ok(Status {
        initialized: true,
        account_id: binding.as_ref().map(|binding| binding.profile_id),
        key_id: binding.as_ref().map(|binding| binding.key_id),
        material_present: material.is_some(),
        prepared: binding.is_some() && material.is_some(),
    })
}
pub fn inspect(path: &Path, profile: Uuid, material: Option<&Material>) -> Result<Status, Error> {
    if profile.is_nil() {
        return Err(Error::Invalid);
    }
    if let Some(material) = material {
        material.validate(profile)?;
    }
    if !existing(path)? {
        return Ok(Status {
            initialized: false,
            account_id: None,
            key_id: None,
            material_present: material.is_some(),
            prepared: false,
        });
    }
    let mut store = Store::open_panel_reader(path).map_err(storage)?;
    let binding = store.sync_workspace_binding().map_err(storage)?;
    if binding
        .as_ref()
        .is_some_and(|bound| bound.profile_id != profile)
    {
        return Err(Error::Account);
    }
    status(binding, material)
}
pub fn prepare(path: &Path, profile: Uuid, material: &Material) -> Result<Status, Error> {
    material.validate(profile)?;
    if !existing(path)? {
        return Err(Error::Unavailable);
    }
    let mut store = Store::open_backup_writer(path).map_err(storage)?;
    let binding = material.binding();
    store.prepare_sync_workspace(&binding).map_err(storage)?;
    // The transaction already validated and committed exactly this identity.
    status(Some(binding), Some(material))
}

/// Key-only recovery. The generic envelope authenticates the root and this
/// purpose/account/key-id payload together. No records, token or cursor included.
pub fn create_recovery(material: &Material) -> Result<(RecoveryCode, Vec<u8>), Error> {
    material.validate(material.profile)?;
    let mut payload = Zeroizing::new(Vec::with_capacity(RECOVERY_DOMAIN.len() + 32));
    payload.extend_from_slice(RECOVERY_DOMAIN);
    payload.extend_from_slice(material.profile.as_bytes());
    payload.extend_from_slice(material.key_id.as_bytes());
    let result =
        zapime_crypto::create_recovery(&material.key, &payload).map_err(|_| Error::Generation)?;
    if result.1.len() > MAX_RECOVERY_PACKAGE {
        return Err(Error::Generation);
    }
    Ok(result)
}
pub fn restore_recovery(profile: Uuid, code: &[u8], package: &[u8]) -> Result<Material, Error> {
    if profile.is_nil()
        || code.len() != 43
        || package.is_empty()
        || package.len() > MAX_RECOVERY_PACKAGE
    {
        return Err(Error::Recovery);
    }
    let code = std::str::from_utf8(code)
        .ok()
        .and_then(|text| RecoveryCode::parse(text).ok())
        .ok_or(Error::Recovery)?;
    let (key, payload) =
        zapime_crypto::restore_recovery(&code, package).map_err(|_| Error::Recovery)?;
    let metadata = payload
        .strip_prefix(RECOVERY_DOMAIN)
        .filter(|metadata| metadata.len() == 32)
        .ok_or(Error::Recovery)?;
    let account = Uuid::from_slice(&metadata[..16]).map_err(|_| Error::Recovery)?;
    let key_id = Uuid::from_slice(&metadata[16..]).map_err(|_| Error::Recovery)?;
    if account.is_nil() || key_id.is_nil() || account != profile {
        return Err(Error::Recovery);
    }
    Ok(Material {
        profile,
        key_id,
        key,
    })
}
