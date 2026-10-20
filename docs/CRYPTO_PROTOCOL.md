# 密文与恢复封装 v1（开发中）

实现 `crates/zapime-crypto`，采用 RustCrypto 的 XChaCha20-Poly1305 与 Argon2id，未自行实现密码算法。当前模块尚未接入平台 Keychain/Keystore、账号、配对、服务器或键盘 UI，不等于完整同步产品，也尚未经过独立安全审计。

## 单条记录

根密钥由系统 CSPRNG 生成 32 字节，nonce 每次随机生成 24 字节，密文含 Poly1305 tag。明文上限 65536 字节。v1 envelope 为 version、URL-safe 无 padding base64 nonce、ciphertext；拒绝未知字段、未知版本、非法长度和解密失败。

AAD 按字节拼接：ASCII `ZapIME/record/v1` + profile UUID 的 16 字节 + record UUID + operation UUID + key UUID + 1 字节 kind（1词库/2候选规则/3短语/4收藏/5剪贴板）。UUID 为 RFC 网络字节顺序，无文本分隔符或本机字节序。更换服务器无需改变 profile；不同对象、操作或 key epoch 的密文不可互换。元数据字段并非保密，服务端仍可能看到对象数量、大小和请求时间。

## 全设备丢失恢复

随机生成 32 字节恢复码并以 base64url 无 padding 显示（43字符）；它本身不是账户密码。用户须独立保管，不能只放到同一份未解锁云备份。Argon2id v0x13，m=32768 KiB、t=2、p=1、输出32字节；随机 salt16 字节。参数固定，不接受包内任意内存参数。

恢复明文为根密钥32字节 + 版本化本地 JSON 归档，用新的随机 nonce24 通过 XChaCha20-Poly1305 加密。AAD 为 ASCII `ZapIME/recovery/v1/argon2id-m32768-t2-p1`。恢复 envelope 含 version、固定 kdf 名、salt、nonce、ciphertext。解密成功后才交给 zapime-data 校验归档并原子恢复到空数据库。

若恢复码和所有已授权设备密钥同时丢失，密文不可恢复；服务器没有解密后门。当前演练只覆盖模块层的新数据库与恢复密钥，不代表空服务器迁移、四端恢复或平台受保护密钥存储已经完成。

## 测试和边界

已验证同文不同密文、错密钥、对象元数据错配、包篡改、未来版本、错恢复码，以及丢弃原密钥后解密旧密文并重建新 SQLite 数据库。SecretKey 不实现 Debug/Serialize；密钥、恢复码和解密输出采用 zeroize 管理。

仍需：官方已知向量、模糊测试、平台安全存储、配对身份认证、撤销后密钥轮换、防重放服务端修订、资源压力与性能评测、独立审计。撤销设备不能让它忘记此前已经获得的明文或旧密钥。
