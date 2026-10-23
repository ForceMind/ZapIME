// SPDX-License-Identifier: GPL-3.0-only
//! Authenticate complete pages before transactional application. No network or key storage.
#![forbid(unsafe_code)]
use serde::{
    de::{SeqAccess, Visitor},
    Deserialize, Deserializer, Serialize,
};
use uuid::Uuid;
use zapime_crypto::{Binding, SecretKey};
use zapime_data::{
    pull::{ApplyReport, RemoteRecord},
    sync::UploadOperation,
    Record, Store,
};

const MAX_PAGE: usize = 10 * 1024 * 1024;
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("invalid_pull_response")]
    Invalid,
    #[error("sync_key_unavailable")]
    KeyUnavailable,
    #[error("sync_authentication_failed")]
    Authentication,
    #[error(transparent)]
    Data(#[from] zapime_data::Error),
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Change {
    pub account_id: Uuid,
    /// Server-reported publisher, not an AEAD-authenticated author identity.
    pub device_id: Uuid,
    pub revision: u64,
    pub operation: UploadOperation,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Page {
    #[serde(deserialize_with = "bounded_changes")]
    pub changes: Vec<Change>,
    pub next_cursor: u64,
    pub head: u64,
}
fn bounded_changes<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<Change>, D::Error> {
    struct Bounded;
    impl<'de> Visitor<'de> for Bounded {
        type Value = Vec<Change>;
        fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("at most 100 encrypted changes")
        }
        fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> Result<Self::Value, A::Error> {
            let mut changes = Vec::new();
            while let Some(change) = sequence.next_element()? {
                if changes.len() == 100 {
                    return Err(serde::de::Error::custom("too_many_changes"));
                }
                changes.push(change);
            }
            Ok(changes)
        }
    }
    deserializer.deserialize_seq(Bounded)
}
/// key_for resolves already available local keys; it must not perform network I/O.
/// Missing/tampered/misbound changes reject the entire page before any mutation.
/// Local pending/semantic conflicts then reject the storage transaction without
/// advancing cursor. Conflict copies/resolution and HTTP orchestration are pending.
pub fn apply_encrypted_page<'key, F>(
    store: &mut Store,
    profile: Uuid,
    after: u64,
    bytes: &[u8],
    mut key_for: F,
) -> Result<ApplyReport, Error>
where
    F: FnMut(Uuid) -> Option<&'key SecretKey>,
{
    if bytes.len() > MAX_PAGE || profile.is_nil() {
        return Err(Error::Invalid);
    }
    let page: Page = serde_json::from_slice(bytes).map_err(|_| Error::Invalid)?;
    if after > i64::MAX as u64
        || page.head > i64::MAX as u64
        || after.checked_add(page.changes.len() as u64) != Some(page.next_cursor)
        || page.next_cursor > page.head
        || (page.changes.is_empty() && page.head != after)
    {
        return Err(Error::Invalid);
    }
    let mut verified = Vec::with_capacity(page.changes.len());
    for (index, change) in page.changes.into_iter().enumerate() {
        let op = &change.operation;
        if change.account_id != profile
            || change.device_id.is_nil()
            || change.revision != after + index as u64 + 1
            || op.base_revision >= change.revision
        {
            return Err(Error::Invalid);
        }
        op.to_wire()?;
        let binding = Binding {
            profile_id: profile,
            record_id: op.record_id,
            operation_id: op.operation_id,
            key_id: op.key_id,
            kind: op.kind,
        };
        let key = key_for(op.key_id).ok_or(Error::KeyUnavailable)?;
        let envelope = serde_json::to_vec(&op.envelope).map_err(|_| Error::Invalid)?;
        let plaintext =
            zapime_crypto::open(key, &binding, &envelope).map_err(|_| Error::Authentication)?;
        let record: Record = serde_json::from_slice(&plaintext).map_err(|_| Error::Invalid)?;
        if record.id != op.record_id
            || record.content.kind() != op.kind
            || record.deleted != op.deleted
        {
            return Err(Error::Authentication);
        }
        verified.push(RemoteRecord {
            publisher_id: change.device_id,
            revision: change.revision,
            operation: change.operation,
            record,
        });
    }
    Ok(store.apply_verified_pull(profile, after, page.next_cursor, page.head, &verified)?)
}
