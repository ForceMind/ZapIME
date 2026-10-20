// SPDX-License-Identifier: GPL-3.0-only
//! 标准密码库上的版本化密文与恢复封装；不实现自定义密码算法。
#![forbid(unsafe_code)]
use argon2::{Algorithm, Argon2, Params, Version};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use chacha20poly1305::{
    aead::{Aead, Payload},
    KeyInit, XChaCha20Poly1305, XNonce,
};
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use zeroize::Zeroizing;

const MAX_ARCHIVE: usize = 32 * 1024 * 1024;
const RECOVERY_AAD: &[u8] = b"ZapIME/recovery/v1/argon2id-m32768-t2-p1";
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("invalid_envelope")]
    Invalid,
    #[error("authentication_failed")]
    Authentication,
    #[error("random_source_unavailable")]
    Random,
    #[error("key_derivation_failed")]
    Kdf,
}

// 不实现 Debug、Display 或 Serialize，防止密钥出现在日志、错误和普通归档。
pub struct SecretKey(Zeroizing<[u8; 32]>);
pub struct RecoveryCode(Zeroizing<String>);
impl SecretKey {
    pub fn generate() -> Result<Self, Error> {
        let mut key = Zeroizing::new([0; 32]);
        random(key.as_mut())?;
        Ok(Self(key))
    }
}
impl RecoveryCode {
    /// 仅在向用户显示或写入受保护恢复资料时调用，不可写入日志。
    pub fn expose(&self) -> &str {
        &self.0
    }
    pub fn parse(text: &str) -> Result<Self, Error> {
        if text.len() != 43 || decode(text)?.len() != 32 {
            return Err(Error::Invalid);
        }
        Ok(Self(Zeroizing::new(text.to_owned())))
    }
}
fn random(buffer: &mut [u8]) -> Result<(), Error> {
    OsRng.try_fill_bytes(buffer).map_err(|_| Error::Random)
}
fn decode(value: &str) -> Result<Vec<u8>, Error> {
    URL_SAFE_NO_PAD.decode(value).map_err(|_| Error::Invalid)
}
fn cipher(key: &SecretKey) -> XChaCha20Poly1305 {
    XChaCha20Poly1305::new_from_slice(key.0.as_ref()).expect("fixed-size key")
}

/// 元数据绑定防止服务端把一个对象的密文替换到另一个对象或操作下。
#[derive(Clone)]
pub struct Binding {
    pub profile_id: Uuid,
    pub record_id: Uuid,
    pub operation_id: Uuid,
    pub key_id: Uuid,
    pub kind: u8,
}
impl Binding {
    fn aad(&self) -> Result<Vec<u8>, Error> {
        if self.profile_id.is_nil()
            || self.record_id.is_nil()
            || self.operation_id.is_nil()
            || self.key_id.is_nil()
            || !(1..=5).contains(&self.kind)
        {
            return Err(Error::Invalid);
        }
        let mut aad = b"ZapIME/record/v1".to_vec();
        for id in [
            &self.profile_id,
            &self.record_id,
            &self.operation_id,
            &self.key_id,
        ] {
            aad.extend_from_slice(id.as_bytes());
        }
        aad.push(self.kind);
        Ok(aad)
    }
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    version: u8,
    nonce: String,
    ciphertext: String,
}

pub fn seal(key: &SecretKey, binding: &Binding, plaintext: &[u8]) -> Result<Vec<u8>, Error> {
    if plaintext.len() > 65536 {
        return Err(Error::Invalid);
    }
    let aad = binding.aad()?;
    let mut nonce = [0; 24];
    random(&mut nonce)?;
    let ciphertext = cipher(key)
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: &aad,
            },
        )
        .map_err(|_| Error::Authentication)?;
    serde_json::to_vec(&Envelope {
        version: 1,
        nonce: URL_SAFE_NO_PAD.encode(nonce),
        ciphertext: URL_SAFE_NO_PAD.encode(ciphertext),
    })
    .map_err(|_| Error::Invalid)
}
pub fn open(key: &SecretKey, binding: &Binding, bytes: &[u8]) -> Result<Zeroizing<Vec<u8>>, Error> {
    if bytes.len() > 90000 {
        return Err(Error::Invalid);
    }
    let envelope: Envelope = serde_json::from_slice(bytes).map_err(|_| Error::Invalid)?;
    if envelope.version != 1 {
        return Err(Error::Invalid);
    }
    let nonce = decode(&envelope.nonce)?;
    let ciphertext = decode(&envelope.ciphertext)?;
    if nonce.len() != 24 || ciphertext.len() > 65552 || ciphertext.len() < 16 {
        return Err(Error::Invalid);
    }
    let aad = binding.aad()?;
    cipher(key)
        .decrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: &ciphertext,
                aad: &aad,
            },
        )
        .map(Zeroizing::new)
        .map_err(|_| Error::Authentication)
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct RecoveryEnvelope {
    version: u8,
    kdf: String,
    salt: String,
    nonce: String,
    ciphertext: String,
}
fn derive(code: &RecoveryCode, salt: &[u8]) -> Result<SecretKey, Error> {
    let params = Params::new(32768, 2, 1, Some(32)).map_err(|_| Error::Kdf)?;
    let mut output = Zeroizing::new([0; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(code.expose().as_bytes(), salt, output.as_mut())
        .map_err(|_| Error::Kdf)?;
    Ok(SecretKey(output))
}
/// 恢复包同时封装根密钥与开放数据归档，丢失全部设备后仍可恢复。
pub fn create_recovery(key: &SecretKey, archive: &[u8]) -> Result<(RecoveryCode, Vec<u8>), Error> {
    if archive.len() > MAX_ARCHIVE {
        return Err(Error::Invalid);
    }
    let mut raw_code = Zeroizing::new([0; 32]);
    random(raw_code.as_mut())?;
    let code = RecoveryCode(Zeroizing::new(URL_SAFE_NO_PAD.encode(raw_code.as_ref())));
    let mut salt = [0; 16];
    let mut nonce = [0; 24];
    random(&mut salt)?;
    random(&mut nonce)?;
    let recovery_key = derive(&code, &salt)?;
    let mut payload = Zeroizing::new(Vec::with_capacity(32 + archive.len()));
    payload.extend_from_slice(key.0.as_ref());
    payload.extend_from_slice(archive);
    let ciphertext = cipher(&recovery_key)
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: &payload,
                aad: RECOVERY_AAD,
            },
        )
        .map_err(|_| Error::Authentication)?;
    let envelope = RecoveryEnvelope {
        version: 1,
        kdf: "argon2id-m32768-t2-p1".into(),
        salt: URL_SAFE_NO_PAD.encode(salt),
        nonce: URL_SAFE_NO_PAD.encode(nonce),
        ciphertext: URL_SAFE_NO_PAD.encode(ciphertext),
    };
    Ok((
        code,
        serde_json::to_vec(&envelope).map_err(|_| Error::Invalid)?,
    ))
}
pub fn restore_recovery(
    code: &RecoveryCode,
    bytes: &[u8],
) -> Result<(SecretKey, Zeroizing<Vec<u8>>), Error> {
    // 在解析和运行 KDF 前固定资源边界，不接受调用方指定的任意内存参数。
    if bytes.len() > 46 * 1024 * 1024 {
        return Err(Error::Invalid);
    }
    let envelope: RecoveryEnvelope = serde_json::from_slice(bytes).map_err(|_| Error::Invalid)?;
    if envelope.version != 1 || envelope.kdf != "argon2id-m32768-t2-p1" {
        return Err(Error::Invalid);
    }
    let salt = decode(&envelope.salt)?;
    let nonce = decode(&envelope.nonce)?;
    let ciphertext = decode(&envelope.ciphertext)?;
    if salt.len() != 16
        || nonce.len() != 24
        || ciphertext.len() < 48
        || ciphertext.len() > MAX_ARCHIVE + 48
    {
        return Err(Error::Invalid);
    }
    let recovery_key = derive(code, &salt)?;
    let mut payload = Zeroizing::new(
        cipher(&recovery_key)
            .decrypt(
                XNonce::from_slice(&nonce),
                Payload {
                    msg: &ciphertext,
                    aad: RECOVERY_AAD,
                },
            )
            .map_err(|_| Error::Authentication)?,
    );
    if payload.len() < 32 || payload.len() > MAX_ARCHIVE + 32 {
        return Err(Error::Invalid);
    }
    let mut root = Zeroizing::new([0; 32]);
    root.copy_from_slice(&payload[..32]);
    let archive = Zeroizing::new(payload.split_off(32));
    Ok((SecretKey(root), archive))
}
