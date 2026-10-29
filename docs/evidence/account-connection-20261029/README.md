# Android 手工设备连接验收 · 2026-10-29

代码 `3b0ce4f0e5d4471cc744f0b6a7d5fc211b3ad483` 经 [PR #63](https://github.com/ForceMind/ZapIME/pull/63) 合入 `ac773fe34bd478edeac31a65433b60b991f0bfe9`。CI 检出临时合并 `083f057fcd91f8876297cb358d480d51692b801a`；三者代码树均为 `bd175b34639d5a062dc16d37b94235f6c07eeebd`。跟踪 [#62](https://github.com/ForceMind/ZapIME/issues/62)。本目录后续仅归档文档和原件，不改变已验证的运行时代码。

## 实际结果

[Android 40840760306](https://github.com/ForceMind/ZapIME/actions/runs/40840760306) 双架构构建及三个独立模拟器作业全部 success；没有重跑或省略旧门禁。

| Android API | 连接本机合成检查 | 空 PCM 检查 | 语音事务/Editable 合成检查 | 实际产品 UID | IPv4/IPv6 拒绝并清理 |
| --- | --- | --- | --- | --- | --- |
| 28 | 7/7 | 6/6 | 20/20 | 10067 | 通过 |
| 35 | 7/7 | 6/6 | 20/20 | 10209 | 通过 |
| 36 | 7/7 | 6/6 | 20/20 | 10216 | 通过 |

每个系统保留原两轮真实系统输入、JNI 合成音解码、录音/权限、空结果确认保护、真实切回系统 IME 和服务退出门禁。`combined-gates.json` 两个退出码均为 0；`offline-uid.json` 的两个拒绝字段及 removed 均为 true。七类新增检查是严格 JSON、身份匹配、真实 Keystore 域隔离、保存/切换/移除、主线程拒绝、预取消和敏感 Activity 生命周期。

[独立服务 40840760234](https://github.com/ForceMind/ZapIME/actions/runs/40840760234) 实际强制 PostgreSQL + race 的 `TestSessionIdentityAndRevocation` PASS，原 S3、容器、漏洞门禁、Rust 密文/新服务器恢复及一键安装重跑/另一空桶恢复均通过。`service-selected.log` 是原始作业日志中有关验证的逐行摘录，不是完整日志；完整原件在 Actions。服务测试没有用户服务器、真实 Cloudflare 凭据或公网 ACME 验收。

另外通用 40840760296（三系统 Rust、Apple 构建及 3 个 iOS 测试）、Windows 40840760319、离线 CPU 40840760272 均 success。`workflows.json` 保留查询时的五条最终状态及精确源码。

## 原件与包核验

`api-28/`、`api-35/`、`api-36/` 中 JSON、分轮 instrumentation、生命周期、退出和运行时日志从对应 ZIP 原样复制，未重写字段或正文。全量原始 ZIP 的大小及 SHA 见 `artifacts.json`，三份均已实际下载并完整校验；只选择相关文本报告入 Git，截图、音频、模型、APK 和庞大的系统 input-method-state 转储留在原始 ZIP。`manifest.json` 记录本目录每个文件的字节数/SHA及来源，清单不包含自身。

`package-verification.json` 来自本地对实际开发 ZIP、双架构 APK、生产 DEX、固定模型六成员与许可资产的核验。产品 DEX 包含连接和原语音事务实现，不含测试探针；模型权重及合成 PCM 不打入产品。签名、manifest 明文禁用和 16KiB 对齐由本源 CI 检查，本地没有冒称重新签名或真机运行。固定下载见 [ANDROID_TRYOUT](../../ANDROID_TRYOUT.md)。

`local-java-compile.json` 记录官方 API36 jar、产品 30 源/54 类、探针 4 源/10 类及对应源文件 SHA；只是 Java 编译。`local-tls-report.json` 和 API28 ZIP 中的主机报告分别为实际 JVM 回环 TLS 31 项通过，测试 CA 仅在独立测试 JVM 信任，产品没有证书或主机名绕过。

## 验收边界

本切片只验证手工设备授权与本机加密保存，`GET /v1/session` 不读写 R2 或同步游标。Android 七类探针使用合成身份，没有 Android 正向 HTTPS、用户服务器部署、资料库绑定、密钥配对/恢复、上传/拉取或四端互通证据。设备令牌不是 E2EE 解密密钥。原语音事务合成结果也不是自然麦克风、物理非空确认或 ARM/三星质量验收；开发签名不是正式发行。
