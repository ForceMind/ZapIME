# ZapIME · 闪电输入法

**一个输入法，连接所有设备。**

面向 Android、iOS、macOS、Windows 的原生中文输入法工程。以离线输入、统一个人数据、自建加密同步与长期可恢复为目标。

## 当前执行目标与接管入口（2026-11-06）

**优先交付可以给用户和 VoiceKey 作者试用的 Android 双离线引擎 Alpha。** X-ASR 保持默认，VoiceKey 微信离线作为第二套可选方案；四端、同步和英语伴学的长期目标不取消，但不阻塞本次 Android 交付。

双离线候选保存在 `feat/voicekey-engine-import` / PR #71；后续本机准备为PR #73、服务器密钥登记为PR #74，最新手动同步开发分支为 `feat/android-manual-sync`。继续跟踪 Issue #72，尚未合并 main；不要只从 main 的历史提示词接管而遗漏这些堆叠分支。

[新开发计划与目标](docs/DEVELOPMENT_PLAN.md) · [当前状态](docs/STATUS.md) · [VoiceKey 整合方案](docs/VOICEKEY_INTEGRATION.md) · [证据与验收矩阵](docs/VOICEKEY_ACCEPTANCE.md) · [工作会话接管提示词](docs/WORK_HANDOFF_PROMPT.md)

## 当前状态

开发中，尚未发布完整产品。已固定 MSIME 共享源码，新增离线 Rust 会话适配与独立 Android 原生键盘；Android 双架构开发包已构建，API28/35/36 模拟器两轮输入、原有离线语音与真实服务退出已有证据；同编辑器组字重启回归已接入，交接期间快速输入及真机仍需验证。已增加共享 SQLite 个人数据、持久上传准备层、认证加密恢复模块，以及 Android 离线管理、候选置顶及明文/加密备份恢复入口。其余三端仍在原生验收中；独立 Go/PostgreSQL 密文服务已通过实际数据库、容器与空库恢复测试，四端云同步、配对及完整恢复体验尚未完成。完整证据见[项目状态](docs/STATUS.md)及保留的历史记录。

macOS InputMethodKit 和 iOS 原生键盘扩展已有独立宿主及原生构建/桥接证据；构建方式和验证边界见 [Apple 开发宿主](docs/APPLE_NATIVE.md)。Windows 最小 TSF 宿主已有源码及 DLL/COM/词库基线通过；独立进程系统 profile 验收仍需按 [Windows 开发宿主](docs/WINDOWS_NATIVE.md) 和[系统验收报告](docs/WINDOWS_ACCEPTANCE_REPORT.md)继续处理，不能用通用 CI 代替。仓库按用户授权保持公开，签名与用户私密资料不进入源码。

Android 已有九键/全键、明暗配色、遵循系统的按键反馈、连续退格、常用表情和显式光标/剪贴板编辑。原生界面的布局、实际模拟器截图与验证边界见 [Android UI](docs/design/ANDROID_UI.md)。原有开发版已接入离线语音模型导入、录音预览/停止/重录及确认插入；范围、安装与原始证据见 [九键与本地语音](docs/VOICE_INPUT.md)。[整句质量诊断](docs/VOICE_QUALITY.md)保存合成语音的全部错误；真人识别率、ARM/三星及另外三端仍需独立验收。

[本机资料加密备份](docs/LOCAL_ENCRYPTED_BACKUP.md)使用独立恢复码、写后读回与空白未绑定资料库恢复。API28/35/36的真实系统文件导出、实际文件恢复到独立空库及隐私/生命周期门禁已有证据；正向SAF导入界面和真机仍待验收。它不包含云同步密钥或账号恢复能力，开放[格式说明](docs/LOCAL_BACKUP_FORMAT.md)便于独立迁移。

Android 双离线引擎 Alpha 已提供[固定 APK、模型准备与试用指南](docs/DUAL_VOICE_ALPHA_TRYOUT.md)。候选通过 API28/35/36 的 VoiceKey 专项，手机及自然人声仍待验收；旧[安装指南](docs/ANDROID_TRYOUT.md)仅对应其历史包，不能混用 SHA 和证书。

键盘内可搜索本机短语、收藏和显式保存的剪贴板，直接插入完整内容，并置顶、转收藏或确认删除。搜索词与聊天正文隔离，密码/禁止学习字段拒绝访问；全部使用本机数据。详见 [本机资料面板](docs/LOCAL_DATA_PANEL.md)。

规划中的[英语伴学](docs/ENGLISH_LEARNING.md)将在中文上屏后展示参考英译，并通过主动收藏积累中英对照。请求生命周期策略已有基线；Android增加接受提交来源隔离与有界短句组装，生产默认不捕获，仍无可用翻译开关；已完成[OPUS 2020预处理对照](docs/ENGLISH_TRANSLATION_PREPROCESSING.md)、[Qwen固定配置CPU研究](docs/ENGLISH_TRANSLATION_QWEN_PROBE.md)和[OPUS 2021研究](docs/ENGLISH_TRANSLATION_OPUS_2021_PROBE.md)，保留全部合成样本及语义问题。翻译模型尚未接入输入法宿主/界面，当前探针不作为默认英语教学输出。

服务器一键安装、自有服务器中转与 Cloudflare R2 密文存储见 [服务器部署](docs/SERVER_DEPLOY.md)。服务器新增[密钥身份登记](docs/SYNC_KEY_DESCRIPTOR.md)；完整四端互通、配对与灾备仍需独立验收。

Android 的[服务器与设备连接](docs/ACCOUNT_CONNECTION.md)提供显式HTTPS授权验证及独立Keystore凭据保护；[本机密钥准备](docs/ANDROID_SYNC_PREPARATION.md)提供创建/恢复、资料库身份绑定和独立恢复文件。固定源码931d30的API28/35/36实际JNI与新页SAF流程已通过并下载重验。最新源码继续接入独立密钥核对/首次登记页面及[手动加密同步](docs/ANDROID_MANUAL_SYNC.md)：用户确认一次才传输，冻结请求重试、严格ACK与整页认证，不开启后台轮询。新传输切片的对应CI、Android HTTPS与真机证据单独记录，不能沿用931准备层通过结论。固定08e84980语音Alpha包不包含这些后续功能。

## VoiceKey 双离线引擎整合

完整 `J3n5en/voicekey` 固定于 `vendor/voicekey`。`integrations/voicekey` 直接引用固定上游的 `fbank.rs` / `xnet.rs`，在保留来源的本地 `model.rs` / `stream.rs` / `kernels.rs` 中实现取消检查、精确零输入策略及稳定 NEON 兼容。适配器只编译离线模块，不编译非公开云协议客户端。Android 已有独立 JNI、显式模型导入、引擎选择与录音分流；X-ASR 仍为默认。

当前候选产品源码固定为 `08e84980562ecc8fab436ffcdab6e5aee8c567e7`。已修复仅装 VoiceKey 的入口、可信固定 SHA 模型导入、音频背压、共享停止预算、协作取消、缓存与权限复查。双 ABI APK 已实际下载，校验源码、签名、JNI、独立依赖许可和 16KiB 对齐。产品不变的测试提交 `be79d084` 在 [run42227414903](https://github.com/ForceMind/ZapIME/actions/runs/42227414903) 完成 API28/35/36 仅装 VoiceKey、真实模型/JNI、预览和确认、离线系统 IME 生命周期验收；原有输入/X-ASR/资料/备份回归单独保留。

原生 Linux ARM/x86 的数值测试和固定模型探针通过。单句合成音仍有明显识别错误，不能推导真人识别率或推荐第二引擎。三星 S25/ARM Android、自然人声、同机质量和能耗、正式签名未验收，尚未发布稳定版。模型源包及成员尺寸/SHA已固定；完整性不等于再分发许可，产品及公开测试产物不捆绑 VoiceKey 权重。详见[试用说明](docs/DUAL_VOICE_ALPHA_TRYOUT.md)、[验收矩阵](docs/VOICEKEY_ACCEPTANCE.md)与[集中真机清单](docs/S25_DUAL_VOICE_CHECKLIST.md)。

用户已确认朋友同意源码整合。继续保留署名并整理授权记录；第三方模型、运行库和服务权限单独核查，不默认分发权重、不上传录音、不启用厂商非公开云渠道。见 [整合工程](integrations/voicekey/README.md)、[技术方案](docs/VOICEKEY_INTEGRATION.md)和 [ADR-020](docs/ADR_020_VOICEKEY_DELIVERY.md)。

## 开发

```sh
git submodule update --init
cargo test --workspace --locked
# 独立 VoiceKey 工作区与依赖锁：
cargo test --locked --manifest-path integrations/voicekey/Cargo.toml
bash scripts/test_android_voice_engine.sh
bash scripts/test_android_voicekey_models.sh
```

[构建说明](docs/BUILD.md) · [新开发计划](docs/DEVELOPMENT_PLAN.md) · [架构](docs/ARCHITECTURE.md) · [接管指令](docs/WORK_HANDOFF_PROMPT.md)

## 数据与隐私原则

本地输入不等网络。个人词库、自定义候选、短语、永久收藏与临时剪贴板使用可迁移的数据体系。云同步计划采用用户自持恢复材料的端到端加密；服务端可替换，四端均保留离线副本。新Android源码只在主App显式设备检查、密钥登记或逐轮确认同步后联网，禁止明文HTTP；键盘和离线语音不依赖网络。没有默认上传或后台轮询，当前不宣称四端同步完成。

## 许可与来源

客户端采用 GPL-3.0-only，保留 [MSIME](https://github.com/metasequoiaime/msime) 版权及第三方许可。上游固定在 `vendor/msime` 子模块；[来源锁](third_party/upstream-lock.json)、[许可审计](docs/LICENSE_AUDIT.md) 与 [NOTICE](third_party/NOTICE.txt) 记录各组件范围。分发二进制须履行对应源码等义务。未将授权不明确的 msime-cloud 实现复制进项目。VoiceKey 的原作者归属、用户确认的授权及第三方模型边界独立记录，不能笼统把所有上游资产宣称为同一种许可。

zapime.com / zapime.app 仍是候选域名，未确认购买或绑定。

独立服务源码、启动和备份说明见 [services/sync](services/sync/README.md)，协议见 [SYNC_PROTOCOL.md](docs/SYNC_PROTOCOL.md)。Android新增手动授权、密钥身份和单轮密文传输接缝；四端资料同步尚未完成验收，不代表已在用户服务器部署。
