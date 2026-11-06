# VoiceKey → ZapIME 双离线引擎整合方案 v2

更新：2026-11-06。此版取代本文件早期“仅方案、未引入源码”的描述。阶段目标见 [开发计划](DEVELOPMENT_PLAN.md)，实际验证范围见 [验收矩阵](VOICEKEY_ACCEPTANCE.md)，最新决策见 [ADR-020](ADR_020_VOICEKEY_DELIVERY.md)。

## 1. 来源与现状

ZapIME 原有 Android 语音路径为固定 MSIME LocalAsr + sherpa-onnx 1.13.8 / ONNX Runtime 1.28.2 + X-ASR-zh-en 流式 INT8，仍为默认。VoiceKey 源码固定于 `vendor/voicekey`，来源 `J3n5en/voicekey@3ba017826eef7fac405198510b03859eb5b6c8c4`。完整源码已以子模块引入，而非把全部上游功能自动打进产品。

`integrations/voicekey` 直接引用固定上游的 `fbank.rs` / `xnet.rs`；`model.rs`、`stream.rs`、`kernels.rs` 是保留作者及固定提交来源的本地适配副本。前两者增加真实推理路径的协作取消、精确零输入策略及边界校验；数值补丁用稳定 NEON 乘法与成对加和替换原 ARM `vdotq_s32`，不再强制 `+dotprod` 或使用旧 `/proc/cpuinfo` 限制。固定 vendor 未修改。x86 单测或 AArch64 编译检查不能证明 ARM 运行数值及速度，仍须独立原生 ARM 对照。

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
| `integrations/voicekey/src/lib.rs` | 有界音频、单物理 worker、缓存和事件 | 真实 worker 退出前保留容量；取消/超时与迟到结果隔离 |
| `integrations/voicekey/src/model.rs` / `stream.rs` | 固定来源的本地推理适配 | 实际计算内协作取消、精确零输入、窗口与尾块边界 |
| `integrations/voicekey/src/android.rs` | JNI 句柄、线程归属及返回值 | 异常隔离、取消竞态、超时后任务回收、正确 PCM 证据 |
| `integrations/voicekey/src/kernels.rs` | 稳定 NEON 兼容补丁 | ARM 数值对照、性能、极值/尾块测试与来源保留 |
| `NativeVoiceKey.java` | 可选 JNI 库绑定 | 实际加载、符号与 ABI 检查 |
| `VoiceKeyModels.java` / `VoiceKeyModelStore.java` / `VoiceKeyModelCatalog.java` | 独立模型管理、受信目录与有界导入 | 固定来源清单、暂存验证、发布恢复与旧有效模型保留 |
| `VoiceEnginePreferences.java` / `VoiceSettingsActivity.java` | 本地选择和模型页面 | 能力可用性、错误可见、切换生效边界 |
| `VoiceEngineSession.java` / `VoicePreviewState.java` | 引擎快照与预览策略 | 会话内不切换；没有新 partial 时保留已有预览 |
| `VoiceCapture.java` / `ZapInputMethodService.java` | 录音调度和语音入口 | 独立模型路由、焦点/隐私与原事务的最多一次确认 |
| `scripts/build_android.sh` / `scripts/check_apk.py` | 双 ABI 打包 | 锁依赖、16KiB、实际符号/动态依赖/资产验证 |

Java 路径均相对于 `platforms/android/java/org/zapime/android/`。

## 4. 启动入口修复与证据边界

在基线 `e24f1bd...` 的 `ZapInputMethodService.startVoice()` 中，准备条件仍为 `VoiceModels.installed(this) || VoiceModels.recoverable(this)`，并未依据 `VoiceEnginePreferences` 选择模型管理器。因此仅导入 VoiceKey、未安装 X-ASR 时会被旧入口条件拦截。此结论来自静态源码检查；本轮未执行真人复现。

当前硬化源码通过 `VoiceEngineSession` 固定所选引擎并调用对应的模型准备路径，不回退另一引擎。本地 JVM 路由/预览回归 12 例已通过，包含仅 X-ASR、仅 VoiceKey、两者都有、均没有、所选模型损坏。入口只作有界能力提示，完整可信验证仍在独立 worker。这是主机回归，不是已激活系统 IME 的真实模型使用；新 Android CI 结果仍须独立记录。

## 5. 必须完成的健壮性审计

以下描述本轮实现和仍需运行验证的边界；主机单测不代替真实模型、Android 或真机。

**会话与取消。** 已移除适配器的 Tokio/async-trait 路径，改为标准库 blocking worker、有界通道及贯穿本地 model/stream 的协作取消。物理解码容量由实际 worker 持有直到退出，不由调用方 drop/超时提前释放；`close` 返回是否完成回收，超时返回不能宣称计算被强制终止。JNI 的 finish 不持句柄锁等待，使另一线程 cancel 可到达相同令牌。主机测试覆盖慢 worker、超时、drop、panic 及取消等待；权限撤销、系统 IME 生命周期与真实模型慢计算仍须新 Android/JNI 验收。

**PCM 与结果。** 只根据成功入队的有效 PCM 前缀累计非零证据。无输入与全零不调用模型 partial/final，±1 保留；该规则不是 VAD 或真实环境静音判定。JNI 显式报告 `partial_updated`，无新值不擦空旧预览；结果携带会话/引擎标识，取消清理且终稿仍须原事务确认。固定 Fbank 的任意分块与整段特征相等已有主机测试；保留其 400 样本窗/160 步长 snip-edges 行为，不把不足一窗的末尾描述为已 flush 解码。

**背压与资源。** 音频队列 8 帧、每帧最多 1600 样本、每会话最多 960000 样本；满队列明确失败并取消，不计入接受样本或非零证据。partial 使用有界最新值槽。缓存身份含路径、长度、修改时间及 Unix inode，检测 app-private 原子替换；模型解析在缓存锁外且验证不写活动缓存，解析 panic 被隔离。新增 `releaseCache`，close/空闲/内存压力路径只释放未被使用的缓存；仍需真实设备观察峰值内存及慢机恢复。

**JNI 与数值。** 已加入 owner/生命周期、参数长度、路径、panic 及 pending Java exception 边界；新增 8 导出/ABI/动态依赖/16KiB 打包硬门禁。适配器本地 20 单测、严格格式、锁定依赖树及 x86/AArch64 clippy 已通过，AArch64 Android `cargo check` 也通过；后者没有链接或运行 Android ELF。x86 release 合成 kernel 计时仅为诊断，不是手机延迟或推荐引擎证据。原生 ARM 数值/性能 workflow 已添加，结果仍待实际执行。

## 6. 可信模型管理

官方来源完整性元数据审计 [run 42209579465](https://github.com/ForceMind/ZapIME/actions/runs/42209579465) 已实际取得固定原包并核对原有尺寸/MD5及所有成员 SHA256/尺寸。人工审查后的 [固定清单](../third_party/voicekey-model-lock.json) 状态为 `PINNED`，`generate_voicekey_catalog.py` 将其生成 Android 编译目录；安装包内自带清单或安装后生成的 hash 不能建立信任。该 run **只通过完整性元数据审计，没有执行推理、JNI 或手机验收，也没有建立模型再分发权利**。

固定模型 ID 为 `wechat-offline-20260710220526250`，原包 SHA256 为 `b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863`。受信目录覆盖原包、已知内层 ZIP、所有外层成员和六个内层成员；导入只发布实际推理所需的 xnet 与 UTF-8 词表。对压缩包尺寸、成员数/总解压设置上限，拒绝未知/重复成员、穿越、符号链接、额外嵌套和哈希不符。未经固定白名单验证，不进入模型解析。

安装采用 app-private 暂存、完整验证、原子发布、失败恢复旧模型；17 项合成存储回归已通过，包括损坏/重复/未知/穿越/符号链接/膨胀、取消、模拟读写失败、中断状态恢复和旧模型保留。模拟 IO 失败不是实际 ENOSPC；文件系统状态夹具不是实际杀进程/断电/SAF 验收。SHA/解压不在主线程；模型不进入个人备份或 R2。X-ASR 白名单保持独立。

用户已确认作者许可源码整合；继续整理可分发授权证据和第三方权利，不再把“是否允许用朋友的代码”当成待用户确认的问题。未经独立权利核验，不在公开产物中捆绑第三方权重、专有运行库或厂商账号凭据。模型获取受阻时完成其余安全代码和测试，并明确列出缺口，不制作假模型或假识别文字。

## 7. CI、评测和交付

独立适配器工作区、Cargo.lock 和上游提交已锁定；构建/测试/许可收集使用 `--locked`。格式、测试和安全检查为硬门禁，不使用 continue-on-error。独立 Rust 许可目录记录锁哈希、实际依赖图及逐文件许可 SHA；本地双 ABI 各 32 个非 dev 包收集与封装清单验证已通过。新 APK 还须实际通过这些门禁，旧包不能替代。

既有 Android 回归必须保持；新增测试要明确选中 VoiceKey 并加载其真实模型。依次区分纯状态机/合成 PCM、模型驱动解码、独立 Editable、已激活 IME、自然麦克风与 ARM 真机证据。专项覆盖和产物要求详见 [验收矩阵](VOICEKEY_ACCEPTANCE.md)。

效率比较区分模型准备/校验、加载、首字、停止到终稿、终稿到确认上屏。相同音频按相同喂入方式分别运行两引擎，记录 RTF、P50/P95、CER、峰值资源、失败。外部 VoiceKey 的快感不能直接等同于本次移植性能；sherpa CPU 探针也不是 S25 的端到端延迟。

## 8. 后续扩展而非本轮默认

先 Android 双引擎闭环，再做桌面原生宿主、iOS 主 App/键盘接力、本地对比候选和经授权的云端能力。保留 VoiceKey 的预热、候选对比、稳定预览等设计价值，但不把多渠道并行常开、无障碍注入或第三方协议原样替换 ZapIME 的原生安全边界。


### 实施补充：背压与共同停止预算

实际focused Android已证实8帧队列在100ms馈入下出现计算峰值。JNI accept仅在Full时最多等待5秒并轮询取消，不改变非阻塞push或容量，不静默丢帧。Java `finish(long)`转发唯一native `finish(long,int)`默认30000；生产录音使用停止后共同预算的剩余值。独立main Handler watchdog发协作取消，UI拒绝期限后结果；物理worker退出前容量仍保留。不能将watchdog或一次async等待解释为物理强制终止。新专项采用真实生产队列和独立绝对时钟producer，不以同步accept变慢作为馈入节奏证明。新实现的实际Android验收以STATUS/原报告为准。
