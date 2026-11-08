# 参与 ZapIME · 闪电输入法

欢迎提交缺陷复现、文档修正、测试证据和代码。Issue 与 Pull Request 可使用中文或英文。

ZapIME 目前处于开发 / Android Alpha 阶段，尚未完成稳定发行验收。Android、Apple、Windows、同步服务和各类模型有各自的验证范围；共享代码、编译成功或模拟器通过不能替代真机、自然人声、正式签名和完整恢复验收。当前能力与已知失败以 [项目状态](docs/STATUS.md) 为准。

## 开始之前

先阅读 [工程约定](AGENTS.md)、[开发计划](docs/DEVELOPMENT_PLAN.md)、[项目状态](docs/STATUS.md) 和 [技术决策](docs/DECISIONS.md)。构建细节见 [BUILD](docs/BUILD.md)，行为准则见 [CODE_OF_CONDUCT](CODE_OF_CONDUCT.md)。

较大的行为、协议、模型或依赖变更，请先提交说明问题、方案和验证边界的 Issue。小型缺陷与文档修正可以直接提交 PR。先检查现有 Issue / PR，避免重复实现或遗漏尚未合并的开发分支。

## 报告问题

使用仓库的缺陷或功能建议表单，提供版本 / commit、平台、系统版本，以及可重复的步骤。Android 请说明设备 / 模拟器、API、ABI、键盘布局；涉及模型时提供引擎、模型版本 / SHA 和冷 / 热启动条件。

公开复现只使用合成文本、合成音频和专用测试账号。例如用“你好，今天去公园”复现候选问题。上传截图、录屏或日志前检查应用正文、通知、账号、路径和元数据；不要提交真实聊天、录音、剪贴板、数据库、恢复包、恢复码、令牌、签名私钥或凭据。不要上传模型权重；提供来源与校验值即可。

安全问题请先阅读 [SECURITY](SECURITY.md)。公开 Issue 只包含无秘密的最小复现；不要公开敏感材料或可用于攻击用户的细节。

## 本地准备与基础检查

```sh
git clone --recurse-submodules https://github.com/ForceMind/ZapIME.git
cd ZapIME
git submodule update --init
python3 scripts/verify_upstream.py
cargo test --workspace --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
```

根目录的 `rust-toolchain.toml` 固定 Rust 1.97.1。保留依赖锁，生成文件放在 `target`，不要提交构建缓存或开发密钥。`vendor` 保留固定上游；不得未经审计更新子模块或为消除检查错误批量改写上游。修改子模块前还须阅读其工程约定。

按改动范围补充下列验证，并在 PR 中写明实际执行结果与未执行原因。文档修正应检查链接、命令与事实；无需声称执行了无关的原生验收。

### Android

需要 Linux / JDK 17、Android API 36、build-tools 35.0.0、NDK 28.2.13676358 与 `zip`。按本机实际路径设置 SDK：

```sh
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
bash scripts/build_android.sh arm64-v8a
bash scripts/build_android.sh x86_64
```

构建产物使用开发签名。不同构建环境的开发证书可能不同，不能假定可覆盖升级；保留旧应用与个人资料备份。APK 构建和包校验不等于已完成系统键盘输入验收。

输入与语音变更应覆盖焦点 / 选区 / 生命周期、过期异步结果、密码和禁止学习字段、取消及资源边界。模型变更应保留固定来源、哈希、许可和失败回退。候选质量评估还须保留冻结语料、基础排序对照与失败样本，见 [候选评测](docs/CANDIDATE_EVALUATION.md)。

Android 双轮、语音、同步 HTTPS 和破坏性脚本仅用于仓库定义的受保护临时 CI。它们可能清除合成 App 数据、切换系统输入法或修改测试 AVD 的网络 / 证书环境，不能在个人日常设备执行或删除保护条件。本地专用设备入口及影响见 [构建说明](docs/BUILD.md)；真机测试应使用对应验收清单并先确认设备范围。

### VoiceKey 独立适配器

该适配器有独立工作区与 `Cargo.lock`；先读 [整合说明](integrations/voicekey/README.md)、[设计](docs/VOICEKEY_INTEGRATION.md) 和 [验收矩阵](docs/VOICEKEY_ACCEPTANCE.md)。相关改动还需运行：

```sh
cargo fmt --manifest-path integrations/voicekey/Cargo.toml --all -- --check --config skip_children=true
cargo test --locked --manifest-path integrations/voicekey/Cargo.toml
cargo clippy --all-targets --locked --manifest-path integrations/voicekey/Cargo.toml -- -D warnings
bash scripts/test_android_voice_engine.sh
bash scripts/test_android_voicekey_models.sh
```

主机单测、实际模型解码、Android JNI、系统 IME、原生 ARM 和自然人声是独立证据。源码整合授权不自动覆盖第三方模型或服务；不把 VoiceKey 权重加入产品或公开测试产物。

### Go / PostgreSQL 同步服务

工具链以 `services/sync/go.mod` 为准。先准备专用 PostgreSQL 16 测试数据库，通过本机环境设置 `ZAPIME_TEST_DATABASE_URL`；连接凭据不要写入源码、命令历史或公开报告。然后运行：

```sh
cd services/sync
export ZAPIME_REQUIRE_DATABASE=1
go test -race -count=1 -v ./...
go vet ./...
```

显式 `SKIP` 不等于数据库事务测试通过。服务协议、备份和隔离演练见 [服务手册](services/sync/README.md)。安装、清库和恢复演练只能在自有或隔离测试环境执行，不能用生产实例验证破坏性流程。

### Apple 与 Windows

分别遵循 [Apple 原生宿主](docs/APPLE_NATIVE.md)、[Windows 原生宿主](docs/WINDOWS_NATIVE.md) 和 [Windows 系统验收](docs/WINDOWS_ACCEPTANCE_REPORT.md)。使用对应系统工具链与原生 CI；跨编译核心库不能替代系统激活、实际编辑器输入、退出、安装或签名验证。

## 提交 Pull Request

每个独立切片使用分支和 PR，保持改动可审查，不强推。说明触发条件、改动后的行为、验证方式和已知限制，关联相关 Issue。新增回归测试应验证可观察行为，保留原始失败，不降低断言或修改语料来制造通过结果。

涉及用户行为、协议、构建或交付范围时，同步更新对应文档；新的架构决定记录到技术决策。证据应包含日期、固定 commit、平台 / API / ABI、命令、原始结果或 CI 链接，并清楚区分源码、编译、模拟器、真机和正式签名。没有执行的项目写明 `NOT RUN` 或阻塞原因；重试成功时保留首次失败。

输入线程不得等待网络或模型推理；个人数据默认本地，上传须显式授权。不要新增默认云回退、正文日志、无界队列或绕过隐私字段的学习。同步、加密、候选身份与语音确认等边界按 [AGENTS](AGENTS.md) 和对应协议维护。

## 许可与来源

ZapIME 自有客户端及独立服务代码采用 GPL-3.0-only；各上游、模型、词库和运行库保留各自的许可与通知。提交改动表示你有权提供该内容，并按所修改组件的适用许可贡献；不要替上游重新许可。

引入依赖、模型或复制适配代码时，记录精确版本 / commit、来源、校验值、原作者与修改范围，更新必要的来源锁及通知。先阅读 [许可审计](docs/LICENSE_AUDIT.md) 和 [第三方通知](third_party/NOTICE.txt)。未经授权的 `msime-cloud` 实现不得复制。开发包也须保留适用许可并提供对应源码与构建资料；完整性校验不能代替再分发授权或正式发布审计。
