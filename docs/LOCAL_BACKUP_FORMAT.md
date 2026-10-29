# 本机加密快照 v1 格式

这是 Android 本机记录备份的可恢复格式，供独立读取工具和未来迁移使用。实现入口为 [local_backup.rs](../crates/zapime-core/src/local_backup.rs)、[恢复封装](../crates/zapime-crypto/src/lib.rs) 和 [记录归档](../crates/zapime-data/src/lib.rs)。不依赖账号、网络服务、设备 Keystore 或官方服务器。用户仍必须保留对应恢复码；此格式不包含已有云同步根密钥。

## 外层封装

文件为 UTF-8 JSON 对象，字段如下，拒绝未知字段和不支持的版本/KDF。

| 字段 | 值或编码 |
| --- | --- |
| `version` | 整数 `1` |
| `kdf` | 固定字符串 `argon2id-m32768-t2-p1` |
| `salt` | 16 个随机字节的无填充 Base64url |
| `nonce` | 24 个随机字节的无填充 Base64url |
| `ciphertext` | XChaCha20-Poly1305 密文及其末尾 16 字节认证标签的无填充 Base64url |

恢复码由 32 个随机字节编码成 43 个无填充 Base64url 字符。解析时必须能解码为 32 字节；KDF 的密码输入是 **43 字符本身的 ASCII 字节**，不是解码后的 32 字节。

Argon2id 使用版本 `0x13`、内存 32768 KiB、迭代 2、并行度 1、输出 32 字节；不接受文件指定的任意参数。该输出用作 XChaCha20-Poly1305 密钥。AAD 是以下 ASCII 字节，不带末尾换行或 NUL：

```
ZapIME/recovery/v1/argon2id-m32768-t2-p1
```

每次创建随机恢复码、salt、nonce 和一个独立 32 字节备份密钥。只有认证成功后才能处理明文。

## 认证明文

解密得到的字节按顺序为：

1. 32 字节独立备份密钥。它只是复用通用恢复封装所需的槽位；本机恢复不会把它装入同步系统，也不能把它当成用户原有同步密钥。
2. ASCII `ZapIME/local-records/v1`，紧接一个值为 0 的字节。
3. UTF-8 记录归档 JSON，格式为 `{"body":{"schema_version":1,"records":[…]},"sha256":"…"}`。

必须验证第 2 项的完整用途前缀。仅通用恢复封装认证成功而用途不符的文件也要拒绝，不能将普通/同步恢复包误读成本机快照并丢弃其真正密钥。

移动端在解析/KDF 前限制整个文件不超过 6 MiB；去除前 32 字节与用途前缀后的记录归档不超过 4 MiB。不是压缩包，不执行文件内路径或任意代码。

## 记录归档

`body` 的字段顺序为 `schema_version`、`records`。每条记录顺序为 `id`、`revision`、`device_id`、`deleted`、`content`。`content` 的 `type` 标签在前，后续字段顺序如下。

| `type` | 后续字段 |
| --- | --- |
| `dictionary` | `scheme`, `code`, `text`, `weight` |
| `candidate_rule` | `scheme`, `code`, `text`, `position` |
| `snippet` | `code`, `text`, `tags` |
| `favorite` | `title`, `text` |
| `clipboard` | `text`, `expires_at_ms`, `pinned` |

`sha256` 是 **经当前类型定义重新序列化的 `body`** 的 SHA-256 小写十六进制，不包含外层 `sha256` 字段。当前使用 `serde_json::to_vec` 的紧凑 UTF-8 JSON；这不是 RFC 8785 的通用对象键排序。数组顺序必须保留，不能为了验散列重新排序记录或标签。UUID 序列化为标准小写带连字符形式，字符串按 serde_json 规则转义，不把非 ASCII 文本统一转为 `\u`。跨语言读取器需要遵守这些字段/序列化规则，或调用随仓库提供的 Rust 实现。

仍须完整校验版本、散列、记录及字段边界：最多 100000 条、UUID 非空且记录 ID 唯一、revision 在 1 至 i64 最大值范围、内容长度/编码/候选位置合法，且有效词库项及候选槽位没有重复。散列不能代替外层 AEAD 认证。剪贴板的绝对过期时间不因恢复延期，逻辑删除记录仍为墓碑。

Android 写入只发生在已存在的 v2 空资料库，同一事务复核 schema 并拒绝记录、账号绑定及操作/同步历史；不会覆盖、自动创建/迁移或清空现有库。独立读取工具可以在认证和完整验证后输出开放归档，但不能把备份中的作者 UUID 当成云账号授权，也不能把本机恢复称为账号配对或完整云灾难恢复。
