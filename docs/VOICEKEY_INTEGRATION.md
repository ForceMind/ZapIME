# VoiceKey → ZapIME 双离线引擎整合方案 v2

更新：2026-11-06。此版取代本文件早期“仅方案、未引入源码”的描述。阶段目标见 [开发计划](DEVELOPMENT_PLAN.md)，实际验证范围见 [验收矩阵](VOICEKEY_ACCEPTANCE.md)，最新决策见 [ADR-020](ADR_020_VOICEKEY_DELIVERY.md)。

## 1. 来源与现状

ZapIME 原有 Android 语音路径为固定 MSIME LocalAsr + sherpa-onnx 1.13.8 / ONNX Runtime 1.28.2 + X-ASR-zh-en 流式 INT8，仍为默认。VoiceKey 源码固定于 `vendor/voicekey`，来源 `J3n5en/voicekey@3ba017826eef7fac405198510b03859eb5b6c8c4`。完整源码已以子模块引入，而非把全部上游功能自动打进产品。

`integrations/voicekey` 当前直接引用上游 `fbank.rs`、`model.rs`、`stream.rs`、`xnet.rs`；`kernels.rs` 是保留来源的最小本地适配副本。原 ARM `vdotq_s32` 在项目固定 Rust 工具链中引发 E0658，现改用稳定 NEON 乘法与成对加和，不再强制 `+dotprod` 或使用旧的 `/proc/cpuinfo` 限制。编译通过不能证明该替代路径与原 VoiceKey 等速，须做数值及 ARM 性能对照。

当前适配器不编译 VoiceKey 的非公开云协议核心、内置第三方服务密钥、CPAL 或 Opus。上游桌面应用、Android 无障碍注入、iOS 宿主和其他引擎保留供研究，不直接接管 ZapIME 的包名、录音、权限或上屏流程。

## 2. 目标架构

```text
显式语音入口 + 当前编辑器/隐私/选区检查
    → 本次会话锁定 engine_id 与 model_id
    → 可信模型管理（X-ASR / VoiceKey 各自独立）
    → ZapIME AudioRecord，16 kHz 单声道 PCM16
    → 独立语音 worker + 有界输入/事件队列
        ├─ X-ASR：现有 NativeVoice / LocalAsr
        └─ VoiceKey：NativeVoiceKey / Rust 离线适配器
    → 带 session/epoch 的 partial 预览与 final 定稿
    → VoiceCommitTransaction 复核原输入框/选区/隐私
    → 用户确认后最多一次 commitText
```

上图是统一产品契约；并不表示所有状态与校验都已实现正确。不得在 UI/按键线程做模型 IO、哈希、解码、等待网络或阻塞式远端编辑器读取。

能力描述至少包含引擎/模型版本、offline、streaming、支持平台和 PCM 格式、准备状态、错误码、资源限制。未安装、校验失败、运行库不可用、用户未授权和平台不支持必须区分。没有云端授权时不得自动建立连接或回退上传。

## 3. 当前代码接点

| 路径 | 用途 | 接续重点 |
| --- | --- | --- |
| `integrations/voicekey/src/lib.rs` | 有界音频、缓存、流式推理和事件 | 取消、终止、缓存版本、资源上限、无输入行为 |
| `integrations/voicekey/src/android.rs` | JNI 句柄、线程归属及返回值 | 异常隔离、取消竞态、超时后任务回收、正确 PCM 证据 |
| `integrations/voicekey/src/kernels.rs` | 稳定 NEON 兼容补丁 | ARM 数值对照、性能、极值/尾块测试与来源保留 |
| `NativeVoiceKey.java` | 可选 JNI 库绑定 | 实际加载、符号与 ABI 检查 |
| `VoiceKeyModels.java` | 用户显式离线包导入 | 固定信任锚、有界提取、原子发布与恢复 |
| `VoiceEnginePreferences.java` / `VoiceSettingsActivity.java` | 本地选择和模型页面 | 能力可用性、错误可见、切换生效边界 |
| `VoiceCapture.java` / `ZapInputMethodService.java` | 录音调度和语音入口 | 选择一致性、焦点/隐私、入口不得依赖另一引擎 |
| `scripts/build_android.sh` / `scripts/check_apk.py` | 双 ABI 打包 | 锁依赖、16KiB、实际符号/动态依赖/资产验证 |

Java 路径均相对于 `platforms/android/java/org/zapime/android/`。

## 4. 已确认的首个修复项

在基线 `e24f1bd...` 的 `ZapInputMethodService.startVoice()` 中，准备条件仍为 `VoiceModels.installed(this) || VoiceModels.recoverable(this)`，并未依据 `VoiceEnginePreferences` 选择模型管理器。因此仅导入 VoiceKey、未安装 X-ASR 时会被旧入口条件拦截。此结论来自静态源码检查；本轮未执行真人复现。

先修复该逻辑并提供五类入口回归：仅 X-ASR、仅 VoiceKey、两者都有、均没有、所选模型损坏。入口只作有界能力提示，最终可信验证仍在独立 worker；不能以修复入口为由把大文件哈希搬到 UI。

## 5. 必须完成的健壮性审计

以下是需以测试核实并收口的风险，不是已通过声明。

**会话与取消。** `abort` 一个异步等待任务不等于已经停止 `spawn_blocking` 内的模型计算。取消令牌须贯穿加载、音频消费、partial、final；超时/取消/close 后不能生成可上屏事件，不能留下无限运行的后台推理。`finish()` 等待期间即使取消或超时，任务和句柄也必须有明确所有者与回收路径。测试重复开始/停止、四句柄容量、权限撤销、键盘隐藏、光标/编辑器切换与慢解码。

**PCM 与结果。** 只根据成功接受的有效 PCM 前缀累计非零证据，不能写死 `has_nonzero_pcm=true`；无输入与全零时 partial/final 都必须为空且避免不必要尾解码。该规则不是 VAD 或真实环境静音判定。没有新 partial 时不要用空值擦掉上一次预览；结果携带来源与会话标识，晚到结果不能串场。终稿不能自动插入或自动学习。

**背压与资源。** 有界队列满时须明确停止/报错或在非 UI worker 有界等待，不能静默丢音；测试 60 秒/960000 样本、尾块、极端慢机、事件洪泛、快速取消再录。模型缓存不能仅按目录路径复用已经替换的权重；导入验证不能污染活动缓存。定义闲置释放与内存压力行为，确保 mmap 文件不被在用时原地覆盖。

**JNI 与数值。** 检查所有入口的 owner/生命周期、长度/空指针/非法路径、panic/异常边界与迟到 cancel；错误不得跨 FFI 杀死输入法。NEON 补丁需要 ARM 上与标量结果对照，不用 x86 测试替代；覆盖负数、边界值、非整块维度等。先保住正确性再优化速度，不为通过构建而切换未审计工具链或移除门禁。

## 6. 可信模型管理

当前 VoiceKey 导入器使用固定上游包尺寸/MD5，并在安装后生成每文件 SHA256。这只能提供有限的包身份及安装后完整性检查，**不等于产品已具备可信固定 SHA256 白名单**。

在稳定使用前需要：从可追溯来源确定固定模型版本、原始包 SHA256、成员路径/长度/SHA256及许可材料；将受信清单绑定 APK/构建，而非相信导入包或新生成的自签清单。大文件/成员数/总解压/嵌套输入都要有上限；拒绝重复成员、路径穿越、符号链接、未知模型组合和异常词表。未经可信校验，不进入本地模型解析或推理。

安装采用 app-private 暂存、完整验证、原子发布、失败恢复旧模型；测导入取消、存储不足、损坏包、发布中断、重复导入、进程重启和使用中替换。SHA/解压不在主线程；语音模型不进入个人备份或 R2。保留 X-ASR 既有可信导入机制，不为兼容第二引擎放宽原白名单。

用户已确认作者许可源码整合；继续整理可分发授权证据和第三方权利，不再把“是否允许用朋友的代码”当成待用户确认的问题。未经独立权利核验，不在公开产物中捆绑第三方权重、专有运行库或厂商账号凭据。模型获取受阻时完成其余安全代码和测试，并明确列出缺口，不制作假模型或假识别文字。

## 7. CI、评测和交付

保持独立适配器工作区与主输入核心隔离，锁定依赖和上游提交；补齐独立 Cargo.lock 后在 CI 使用 --locked。格式、测试和安全检查恢复为硬门禁，不能让 continue-on-error 掩盖失败。格式化本地补丁，不无意改写固定 vendor 源码。

既有 Android 回归必须保持；新增测试要明确选中 VoiceKey 并加载其真实模型。依次区分纯状态机/合成 PCM、模型驱动解码、独立 Editable、已激活 IME、自然麦克风与 ARM 真机证据。专项覆盖和产物要求详见 [验收矩阵](VOICEKEY_ACCEPTANCE.md)。

效率比较区分模型准备/校验、加载、首字、停止到终稿、终稿到确认上屏。相同音频按相同喂入方式分别运行两引擎，记录 RTF、P50/P95、CER、峰值资源、失败。外部 VoiceKey 的快感不能直接等同于本次移植性能；sherpa CPU 探针也不是 S25 的端到端延迟。

## 8. 后续扩展而非本轮默认

先 Android 双引擎闭环，再做桌面原生宿主、iOS 主 App/键盘接力、本地对比候选和经授权的云端能力。保留 VoiceKey 的预热、候选对比、稳定预览等设计价值，但不把多渠道并行常开、无障碍注入或第三方协议原样替换 ZapIME 的原生安全边界。
