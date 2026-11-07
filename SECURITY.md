# 安全状态

ZapIME 仍处于开发阶段，尚未完成发布安全验收。

- 当前 Android 开发源码申请INTERNET且禁止明文HTTP；只有主App显式设备身份检查联网。键盘/语音不依赖网络，资料同步尚未启用，不自动上传按键、候选或剪贴板。
- 拼音解码与准备词库在后台串行执行。密码、数字/电话及 NO_PERSONALIZED_LEARNING 字段使用私密分支。
- zapime-data 本地 SQLite 与 export_plaintext 为明文；导出必须由用户显式选择，逻辑删除不代表安全擦除。Android 已有显式明文导出入口与确认提示；Android已有本机资料加密备份UI；它不含同步根密钥。新增同步密钥恢复页属于独立开发切片，Android系统验收须看对应证据。
- zapime-crypto 提供认证加密与独立恢复包，已具备Android Keystore保护原语；新的本机同步准备接入仍待对应Android系统验收，设备配对及实际资料网络同步尚未完成，不能宣称已实现完整 E2EE 同步。
- 不要将真实输入、密钥、恢复码、认证 Token 或签名证书写入日志或 Git。

协议与限制见 docs/CRYPTO_PROTOCOL.md；来源与许可见 docs/LICENSE_AUDIT.md。仓库公开；公开 Issue 仅提交不含真实秘密的最小复现，不公开凭据、恢复资料或用户正文。


Apple/Windows 开发宿主默认不学习、不联网。独立 Go/PostgreSQL 服务已有真实数据库与合成容器灾备验证，访问凭据只保存摘要，事务内复核撤销与轮换；四端客户端尚未接入，不能宣称完整四端 E2EE 同步。服务默认回环监听，生产需 HTTPS、外层速率限制、监控和后续安全验收。协议见 docs/SYNC_PROTOCOL.md。
