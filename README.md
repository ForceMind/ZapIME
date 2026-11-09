# ZapIME · 闪电输入法

[English](README.en.md) · [文档](docs/README.md) · [开发路线](docs/ROADMAP.md) · [贡献指南](CONTRIBUTING.md)

本地优先的原生中文输入法。让中文输入、个人词库、短语和收藏随设备迁移，并保留离线使用与自主备份的能力。

**当前阶段：Android 开发 Alpha；其他平台为开发宿主。** [v0.1.0-alpha.1](https://github.com/ForceMind/ZapIME/releases/tag/v0.1.0-alpha.1) 是源码预发布，尚未提供正式签名的通用安装包。[发布说明](docs/releases/v0.1.0-alpha.1.md)列明验证范围、许可待办和已知问题。

## 已有能力

- Android 原生全键与九键拼音、读音选择、个人候选规则、明暗主题及系统按键反馈。
- 默认开启本机候选增强：固定 MSIME 解码器生成候选，8M 字符语言模型异步重排已有候选。没有可信上下文时保留基础排序，失败或超时沿用基础结果。
- 双离线语音路径：默认 X-ASR，可选择 VoiceKey 离线适配器；语音模型按固定目录单独导入，录音预览后由用户确认插入。
- 本机资料搜索、短语、显式保存的剪贴板、永久收藏及加密备份。临时剪贴板与永久收藏分开管理。
- Android 显式手动密文同步、可自建的 Go/PostgreSQL 服务和可选 R2/S3 密文存储。设备配对、四端互通和完整灾备仍在开发。

## 平台状态

| 平台 | 已有证据 | 尚未完成 |
| --- | --- | --- |
| Android | 双 ABI 开发构建；API 28/35/36 x86_64 模拟器输入、候选模型、语音与生命周期门禁 | ARM/S25 运行、自然语音与长期质量/能耗、正式升级签名 |
| macOS | 原生宿主构建与桥接 | 系统 TIS/IMK 激活、其他 App 实际上屏 |
| iOS | App/键盘扩展构建与桥接测试 | 系统键盘启用、真机、签名分发 |
| Windows | TSF DLL/COM 开发基线 | 独立进程 profile 门禁、产品激活与 App 上屏 |

精确提交、失败记录和原始证据见 [STATUS](docs/STATUS.md)。原 34 条与新增 64 条冻结候选用例不能代表真实用户准确率；配置重启组字修复已通过同源码三 API 输入与 API35 独立实验，历史失败仍保留，手机验收另行完成。

## 获取与构建

```sh
git clone --recurse-submodules https://github.com/ForceMind/ZapIME.git
cd ZapIME
python3 scripts/verify_upstream.py
cargo test --workspace --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
```

Rust 由 `rust-toolchain.toml` 固定为 1.97.1。两个子模块都必须保持锁定提交；GitHub 的源码压缩包不含子模块内容。Android 需要 JDK 17、API 36、build-tools 35.0.0 和 NDK 28.2.13676358：

```sh
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
rustup target add aarch64-linux-android x86_64-linux-android
bash scripts/build_android.sh arm64-v8a
```

[完整构建说明](docs/BUILD.md)包含独立 VoiceKey 工作区及平台要求。开发 APK 使用开发证书，不能承诺不同构建可覆盖升级；签名冲突时保留旧应用及资料，先阅读[固定候选试用指南](docs/CANDIDATE_RANKING_TRYOUT.md)。源码 Alpha Release 不附 APK 或语音权重。

## 隐私与安全

普通键盘、候选排序和已安装的离线语音不依赖网络。候选只使用受限的当前编辑器上下文；密码、禁止学习等字段排除增强与学习。语音不默认落盘或上传。

联网由主 App 的显式设备身份检查、密钥身份登记或逐轮确认同步触发；没有默认上传或后台轮询。加密备份、恢复材料与服务访问凭据用途不同。SQLite 本地资料并非全库加密，明文导出与逻辑删除的边界见 [SECURITY](SECURITY.md)。

## 参与开发

欢迎提交合成复现、平台兼容性证据、文档和代码改进。开始前阅读 [CONTRIBUTING](CONTRIBUTING.md)、[行为准则](CODE_OF_CONDUCT.md)和 [AGENTS](AGENTS.md)。公开 Issue 不包含真实输入、录音、剪贴板、密钥或恢复码；安全问题按 [安全说明](SECURITY.md)处理。

新工作对话从 [WORK_HANDOFF_PROMPT](docs/WORK_HANDOFF_PROMPT.md)接续，先核对实时分支、PR 与 CI，再按[开发计划](docs/DEVELOPMENT_PLAN.md)推进已有工程。

## 许可与来源

ZapIME 自有代码采用 [GPL-3.0-only](LICENSE)。固定 MSIME、VoiceKey、模型及运行库分别保留原作者、许可和 NOTICE；此声明不将所有第三方内容重新授权为 GPL。

VoiceKey 作者同意整合的事实已记录，但标准公开分发条款仍需补充；候选模型发布方的 Apache-2.0 权重声明与 LCCC 训练来源声明差异也尚待澄清。因此当前不宣称通用二进制或商业再分发许可已全部收口。详见 [许可审计](docs/LICENSE_AUDIT.md)、[VoiceKey 边界](integrations/voicekey/LICENSE)、[第三方 NOTICE](third_party/NOTICE.txt)和[来源锁](third_party/upstream-lock.json)。
