# 安全状态

ZapIME 仍处于开发阶段，尚未完成发布安全验收。

- 当前 Android 原生验证包不申请网络权限，不上传按键、候选或剪贴板。
- 拼音解码与准备词库在后台串行执行。密码、数字/电话及 NO_PERSONALIZED_LEARNING 字段使用私密分支。
- zapime-data 本地 SQLite 与 export_plaintext 为明文；导出必须由用户显式选择，逻辑删除不代表安全擦除。当前尚无用户界面入口。
- zapime-crypto 提供认证加密与独立恢复包，尚未连接 OS 密钥库、设备配对或远端服务，不能宣称已实现完整 E2EE 同步。
- 不要将真实输入、密钥、恢复码、认证 Token 或签名证书写入日志或 Git。

协议与限制见 docs/CRYPTO_PROTOCOL.md；来源与许可见 docs/LICENSE_AUDIT.md。发现问题时向本 Private 仓库提交不含真实秘密的复现材料。
