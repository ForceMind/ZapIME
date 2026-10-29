// SPDX-License-Identifier: GPL-3.0-only
//! Local records only. Not sync key provisioning, account pairing or recovery.
use std::path::Path;
use zapime_crypto::{create_recovery, restore_recovery, RecoveryCode, SecretKey};
use zapime_data::{Error, Store};
use zeroize::Zeroizing;

pub const MAX_ARCHIVE: usize = 4 * 1024 * 1024;
pub const MAX_PACKAGE: usize = 6 * 1024 * 1024;
const DOMAIN: &[u8] = b"ZapIME/local-records/v1\0";

// No Debug/Display/Serialize: the recovery code is only for the user's protected UI.
pub struct LocalBackup {
    pub code: Zeroizing<Vec<u8>>,
    pub package: Vec<u8>,
}
fn database(root: &Path) -> Result<std::path::PathBuf, &'static str> {
    if !root.is_absolute() {
        return Err("local_profile_unavailable");
    }
    Ok(root.join("data/personal.sqlite3"))
}
pub fn create(root: &Path) -> Result<LocalBackup, &'static str> {
    let mut store =
        Store::open_panel_reader(&database(root)?).map_err(|_| "local_profile_unavailable")?;
    let archive = Zeroizing::new(
        store
            .export_backup_snapshot(MAX_ARCHIVE)
            .map_err(|_| "local_snapshot_unavailable_or_too_large")?,
    );
    let mut payload = Zeroizing::new(Vec::with_capacity(DOMAIN.len() + archive.len()));
    payload.extend_from_slice(DOMAIN);
    payload.extend_from_slice(&archive);
    let key = SecretKey::generate().map_err(|_| "local_backup_failed")?;
    let (code, package) = create_recovery(&key, &payload).map_err(|_| "local_backup_failed")?;
    if package.len() > MAX_PACKAGE {
        return Err("local_backup_failed");
    }
    Ok(LocalBackup {
        code: Zeroizing::new(code.expose().as_bytes().to_vec()),
        package,
    })
}
pub fn restore(root: &Path, code: &[u8], package: &[u8]) -> Result<usize, &'static str> {
    if code.len() != 43 || package.is_empty() || package.len() > MAX_PACKAGE {
        return Err("invalid_or_unauthenticated_backup");
    }
    let code = std::str::from_utf8(code).map_err(|_| "invalid_or_unauthenticated_backup")?;
    let code = RecoveryCode::parse(code).map_err(|_| "invalid_or_unauthenticated_backup")?;
    let (_, payload) =
        restore_recovery(&code, package).map_err(|_| "invalid_or_unauthenticated_backup")?;
    let archive = payload
        .strip_prefix(DOMAIN)
        .ok_or("invalid_or_unauthenticated_backup")?;
    if archive.len() > MAX_ARCHIVE {
        return Err("invalid_or_unauthenticated_backup");
    }
    let mut store =
        Store::open_backup_writer(&database(root)?).map_err(|_| "local_profile_unavailable")?;
    store
        .restore_unbound_plaintext(archive)
        .map_err(|error| match error {
            Error::NotEmpty => "restore_requires_fresh_local_profile",
            _ => "local_backup_invalid_archive",
        })
}
