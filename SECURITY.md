# 安全说明 / Security policy

ZapIME 处于开发 Alpha，尚未完成正式发布安全验收。当前只维护最新开发源码，不承诺旧开发包的安全回补或正式签名升级。

## 报告问题

公开仓库不适合提交可利用的漏洞细节、真实输入、录音、剪贴板、密钥、恢复码、Token 或签名私钥。

如仓库启用了 GitHub 私密漏洞报告，请使用 Security 页的私密报告入口。若该入口不可用，可建立仅说明“需要私密安全联系”的 Issue，等待维护者确定通道后再发送细节；仓库目前没有公布专用安全邮箱，也不承诺响应时限。非敏感的合成复现可使用普通 Bug 模板。

## 当前数据与网络边界

- 普通输入、候选模型和已安装的离线语音不等待网络。Android 申请 INTERNET 并禁止明文 HTTP；主 App 只有用户显式设备身份检查、密钥身份登记或逐轮确认资料同步才发起请求。没有默认上传、后台轮询或云端识别回退。
- 候选只使用有界当前会话上下文，隐私/密码/禁止学习等字段排除。输入、语音与同步使用分离的 worker；不得将正文或秘密写入诊断。
- 本地 SQLite 与显式明文导出是明文，逻辑删除不代表安全擦除。Android 本机加密备份不含同步根密钥，不能替代单独的同步恢复材料。
- Android Keystore 保护资料与连接材料使用独立命名空间。缺失/不可读旧材料不会自动重置，移除连接不等于删除 E2EE 材料或解除资料库绑定。
- Android 已接入显式手动密文传输及整页认证事务边界；设备配对、历史密钥管理、四端互通和完整灾备仍未完成。不能将局部加密原语或单模拟器测试称为完整 E2EE 产品安全验收。
- Apple/Windows 开发宿主默认不学习、不联网；相应系统输入门禁仍有未决事项。
- 独立 Go/PostgreSQL 服务保存访问凭据摘要，默认回环监听。部署需 HTTPS、外层速率限制、监控及独立安全验收；仓库 CI 不代表已部署到用户服务器。

协议与限制：[加密](docs/CRYPTO_PROTOCOL.md)、[同步](docs/SYNC_PROTOCOL.md)、[Android 手动同步](docs/ANDROID_MANUAL_SYNC.md)。来源及分发边界见[许可审计](docs/LICENSE_AUDIT.md)。

## English

This is a development Alpha, with no stable security-support or upgrade-signing commitment. Use GitHub private vulnerability reporting if enabled. Otherwise open an issue requesting a private contact **without exploit details or secrets**, and wait for a channel. No dedicated security email or response SLA is currently published.

Normal input and installed offline inference do not need a network. Android networking requires explicit main-app actions; there is no default upload or background polling. Local SQLite and explicit plaintext exports are not encrypted as a whole, and logical deletion is not secure erasure. Encrypted backups, sync recovery material and connection credentials have separate scopes. Manual Android ciphertext transfer exists; pairing, key-history recovery and complete cross-platform E2EE acceptance remain unfinished.
