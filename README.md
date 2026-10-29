# ZapIME · 闪电输入法

**一个输入法，连接所有设备。**

面向 Android、iOS、macOS、Windows 的原生中文输入法工程。以离线输入、统一个人数据、自建加密同步与长期可恢复为目标。

## 当前状态

开发中，尚未发布完整产品。已固定 MSIME 共享源码，新增离线 Rust 会话适配与独立 Android 原生键盘；Android 双架构开发包已构建，API28/35/36 模拟器两轮输入、独立离线语音与真实服务退出已通过；同编辑器组字重启回归已接入，交接期间快速输入及真机仍需验证。已增加共享 SQLite 个人数据、持久上传准备层、认证加密恢复模块，以及 Android 离线管理、候选置顶及明文/加密备份恢复入口。其余三端仍在原生验收中；独立 Go/PostgreSQL 密文服务已通过实际数据库、容器与空库恢复测试，四端云同步、配对及完整恢复体验尚未完成。完整证据见[项目状态](docs/STATUS.md)。

macOS InputMethodKit 和 iOS 原生键盘扩展已有独立宿主及原生构建/桥接证据；构建方式和验证边界见 [Apple 开发宿主](docs/APPLE_NATIVE.md)。Windows 最小 TSF 宿主已有源码及 DLL/COM/词库基线通过；独立进程系统 profile 验收仍失败，见 [Windows 开发宿主](docs/WINDOWS_NATIVE.md) 和[本轮系统验收](docs/WINDOWS_ACCEPTANCE_REPORT.md)。仓库按用户授权保持公开，签名与用户私密资料不进入源码。

Android 已有九键/全键、明暗配色、遵循系统的按键反馈、连续退格、常用表情和显式光标/剪贴板编辑。原生界面的布局、实际模拟器截图与验证边界见 [Android UI](docs/design/ANDROID_UI.md)。开发版已接入离线语音模型导入、录音预览/停止/重录及确认插入，API28/35/36模拟器完整门禁与录音尾块竞态回归已通过；范围、安装与真实原始证据见 [九键与本地语音](docs/VOICE_INPUT.md)。新增[整句质量诊断](docs/VOICE_QUALITY.md)保存合成语音的全部错误；真人识别率、ARM/三星、现代系统后台权限及另外三端仍需验收。

新增[本机资料加密备份](docs/LOCAL_ENCRYPTED_BACKUP.md)：独立恢复码、写后读回与空白未绑定资料库恢复。API28/35/36的真实系统文件导出、实际文件恢复到独立空库及隐私/生命周期门禁已通过；正向SAF导入界面和真机仍待验收。它不包含云同步密钥或账号恢复能力，开放[格式说明](docs/LOCAL_BACKUP_FORMAT.md)便于独立迁移。

需要试用 Android 开发版时，请按[固定构建下载与安装指南](docs/ANDROID_TRYOUT.md)取得 APK 和离线语音模型；指南列出对应源码、文件校验值、启用步骤及开发签名的升级限制。

键盘内可搜索本机短语、收藏和显式保存的剪贴板，直接插入完整内容，并置顶、转收藏或确认删除。搜索词与聊天正文隔离，密码/禁止学习字段拒绝访问；全部使用本机数据。详见 [本机资料面板](docs/LOCAL_DATA_PANEL.md)。

规划中的[英语伴学](docs/ENGLISH_LEARNING.md)将在中文上屏后展示参考英译，并通过主动收藏积累中英对照。请求生命周期策略已有基线；Android增加接受提交来源隔离与有界短句组装，生产默认不捕获，仍无可用翻译开关；已完成[OPUS 2020预处理对照](docs/ENGLISH_TRANSLATION_PREPROCESSING.md)、[Qwen固定配置CPU研究](docs/ENGLISH_TRANSLATION_QWEN_PROBE.md)和[OPUS 2021研究](docs/ENGLISH_TRANSLATION_OPUS_2021_PROBE.md)，保留全部合成样本及语义问题。翻译模型尚未接入输入法宿主/界面，当前探针不作为默认英语教学输出。

服务器一键安装、自有服务器中转与 Cloudflare R2 密文存储见 [服务器部署](docs/SERVER_DEPLOY.md)。客户端同步接入仍未完成。

Android 已新增[服务器与设备连接](docs/ACCOUNT_CONNECTION.md)：手动HTTPS验证设备凭据、独立Keystore保存、再次检查及移除本机连接。当前只验证授权，不上传个人资料；API28/35/36 与真实 PostgreSQL 门禁已通过；保存连接前还会核对已有资料库账号，拒绝跨账号替换。完整配对和数据同步仍未接入。

## 开发

```sh
git submodule update --init
cargo test --workspace --locked
```

[构建说明](docs/BUILD.md) · [开发计划](docs/DEVELOPMENT_PLAN.md) · [架构](docs/ARCHITECTURE.md) · [接管指令](docs/WORK_HANDOFF_PROMPT.md)

## 数据与隐私原则

本地输入不等网络。个人词库、自定义候选、短语、永久收藏与临时剪贴板使用可迁移的数据体系。云同步计划采用用户自持恢复材料的端到端加密；服务端可替换，四端均保留离线副本。新Android源码仅主App手动验证设备连接会联网，禁止明文HTTP；键盘和离线语音不依赖网络，个人数据同步仍未启用。

## 许可与来源

客户端采用 GPL-3.0-only，保留 [MSIME](https://github.com/metasequoiaime/msime) 版权及第三方许可。上游固定在 `vendor/msime` 子模块；[来源锁](third_party/upstream-lock.json)、[许可审计](docs/LICENSE_AUDIT.md) 与 [NOTICE](third_party/NOTICE.txt) 记录各组件范围。分发二进制须履行对应源码等义务。未将授权不明确的 msime-cloud 实现复制进项目。

zapime.com / zapime.app 仍是候选域名，未确认购买或绑定。

独立服务源码、启动和备份说明见 [services/sync](services/sync/README.md)，协议见 [SYNC_PROTOCOL.md](docs/SYNC_PROTOCOL.md)。Android 已接手动设备授权验证；四端资料同步尚未接通，不代表已在用户服务器部署。
