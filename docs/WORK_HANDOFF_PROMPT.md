# ZapIME（闪电输入法）｜双离线语音引擎与 Android Alpha 持续研发接管

> 2026-11-06 实施接续：本轮已实际修复引擎入口、Rust/JNI 取消与缓存/PCM、可信模型导入，并补 CPU/ARM/VoiceKey-only Android 专项门禁。请首先读取最新 `docs/STATUS.md` 和 `docs/evidence/voicekey-runtime-20261106/`，再核验实时分支及 CI；下面历史交接的 e24f1bd 状态不覆盖新实现。官方模型审计 run 42209579465 只证明固定来源/摘要，不代表模型推理或手机通过。
>
> 可由本地 Codex 在仓库接续：`git clone --branch feat/voicekey-engine-import --recurse-submodules https://github.com/ForceMind/ZapIME.git`；已有仓库先 `git status --short` / `git fetch origin`，保留未提交改动，不 reset/force。读 AGENTS 与本页指定文档，继续复用 PR #71 / Issue #72。限定隔离 GitHub 模拟器的验收脚本不可在个人设备执行；本地开发环境可运行不需要设备的 Rust/JVM/host 测试。


你现在接管已有工程的后续开发，担任产品、Rust/Android 原生开发、语音推理、隐私安全、测试与 GitHub 交付负责人。你的任务是读取真实仓库、修改代码、运行测试、修复问题并交付可安装试用包，不是重新设计项目，也不是仅提交文档或建议。

## 一、接续位置与事实核验

仓库：https://github.com/ForceMind/ZapIME

- 名称：ZapIME / 闪电输入法；仓库保持 Public，客户端原有 GPL-3.0-only 与第三方署名义务不改变。
- 主分支：main。
- 当前开发分支：feat/voicekey-engine-import。
- 当前 PR：https://github.com/ForceMind/ZapIME/pull/71
- 跟踪任务：https://github.com/ForceMind/ZapIME/issues/72
- 本轮最后核验的产品代码：e24f1bd21ecd4fca99d909a972116e5a36e5f6c2。
- 当时 main：5621ab0e81780ca58c6cf74e97c93928a10f981a。
- VoiceKey 固定来源：J3n5en/voicekey@3ba017826eef7fac405198510b03859eb5b6c8c4。

这些 SHA 是交接记录，不保证你启动时仍是最新。先查 main、工作分支、PR、Issue、未提交改动和 CI；保护现有工作树，不强推、不覆盖其他人的改动。若 PR 已合并或分支有新进展，从新的真实状态续接，不强行回退到记录 SHA。本提示词和新计划已提交在该实验分支，不能只读 main 的旧文档而漏掉它们。

首先阅读 AGENTS.md、README.md、docs/DEVELOPMENT_PLAN.md、docs/STATUS.md、docs/VOICEKEY_INTEGRATION.md、docs/VOICEKEY_ACCEPTANCE.md、docs/ADR_020_VOICEKEY_DELIVERY.md、integrations/voicekey/README.md，并按需读取 docs/DECISIONS.md、VOICE_INPUT.md、VOICE_QUALITY.md 和原始历史。近期目标与语音实施以这些新文档为准；历史要求未明确变更的继续保留。

## 二、最高优先级交付目标

尽快交付一份可以给我和 VoiceKey 作者试用的 Android 双离线引擎 Alpha：同一个 ZapIME 键盘可以选择 X-ASR 或 VoiceKey 微信离线；各自模型独立准备；断网完成录音、流式预览、停止、取消、重录和确认插入。

X-ASR 仍为默认。不要等 Windows/macOS/iOS、完整云同步或英语伴学全部完成才交付 Android。先把不需要我真机的工作全部完成；缺少 S25 时交付可验证的候选包与一次性真机验收单，不虚构真机结果，也不要因缺手机而停止其余工程。

长期目标不变：四端真实原生输入法；九键/全键；参考 QQ 输入法体验但独立设计的主题与系统触感；统一词库、候选、短语、永久收藏和显式剪贴板；本地数据可迁移；自建服务器中转与 R2 密文存储；低频增量 E2EE 同步与恢复；后续中文上屏后的英语伴学。这些继续沿既有架构开发，不从零重写。

## 三、已完成基础及不能夸大的部分

完整 VoiceKey 源码已以 vendor/voicekey 子模块引入。当前产品适配器只编译离线 wtlocal 的特征、模型、流式解码及本地稳定 NEON 补丁；不编译其非公开云识别客户端或第三方服务密钥。

Android 已有 NativeVoiceKey JNI、模型导入、引擎设置、VoiceCapture 分流和 APK 打包实现。代码基线 e24f1bd 的六条 CI 均已核验 success；Android run 42199407235 已完成双 ABI 构建以及 API28/35/36 现有回归。但这不等于 VoiceKey 模型驱动的完整识别、仅装 VoiceKey 路径、S25 或自然人声准确率已经通过。

开发 artifact 为 13494970099，来源见 docs/VOICEKEY_ACCEPTANCE.md。此前只核验了 GitHub 元数据；你需实际取得 ZIP 并复核大小、SHA、APK 成员、ABI、JNI、对齐、署名和签名。不要把旧 CI 的成功附到新提交冒充已测试，也不要把几百字节的失败产物当安装包。

## 四、立即开始的研发任务

### 1. 先修复已静态确认的启动入口问题

检查 platforms/android/java/org/zapime/android/ZapInputMethodService.java 的 startVoice()。在交接基线中它仍用 VoiceModels.installed/recoverable 判断准备状态，没有按 VoiceEnginePreferences 选择模型管理器，会拦住仅装 VoiceKey 的路径。

实际修复并补充五类测试：仅安装 X-ASR、仅安装 VoiceKey、两者都有、均未安装、所选模型损坏。引擎选择贯穿本轮会话，最终模型哈希与加载留在独立 worker；不得为修复入口把模型 IO 搬进 UI。

### 2. 收口实际运行的安全与资源边界

审计并修复 integrations/voicekey/src/lib.rs、android.rs、kernels.rs，以及 NativeVoiceKey、VoiceKeyModels、VoiceEnginePreferences、VoiceSettingsActivity、VoiceCapture 和已有确认事务的连接。

重点测试：成功接受 PCM 的非零证据；无样本/全零的部分与最终文字必须为空；没有新 partial 时保留正确预览；队列满/尾块/60秒上限；取消、超时、close 后真实推理与句柄回收；重复录音/权限撤销/输入框和光标变化时无迟到上屏；模型缓存替换与闲置释放；异常不跨 JNI 导致崩溃。

不要将 abort 异步任务当成阻塞推理必然停止，不要把写死的状态标记当实测。语音预览不自动上屏，仍通过 VoiceCommitTransaction 对原连接、ticket/epoch、选区和隐私条件复核后最多一次 commitText。

稳定 NEON 补丁已修复原 vdotq_s32 的编译问题。保留来源，在 ARM 上做标量数值对照和资源/速度测试；不因 x86 单测通过就宣称 ARM 等速或适配所有设备。

### 3. 完成可信模型与可重复构建

现有 MD5 包身份检查与安装后生成的每文件 SHA256 不等于可信原包固定白名单。补齐可追溯的版本、包及成员长度/SHA256、来源和授权记录，可信目录绑定构建；拒绝未知成员、重复文件、路径穿越、符号链接、超量输入和未验证模型解析。覆盖取消、磁盘不足、发布中断、重启恢复、旧有效模型保留和使用中替换。

用户已明确确认作者同意集成代码，不要反复询问同一许可。保留作者署名并整理授权证据；第三方权重、专有二进制和厂商服务权限单独处理。不得默认打包未获许可的模型，也不得启用上游提取的凭据或非公开云协议。资源受阻时明确记录，继续其他可执行工作。

锁定适配器 Cargo 依赖和上游。格式/测试应为硬门禁，不用 continue-on-error 隐藏失败，不为了格式化改脏固定 vendor。检查双 ABI、16KiB 对齐、JNI 导出、运行库依赖和包内权重/云代码隔离。

### 4. 新增 VoiceKey 专项验收，而非只重跑旧 X-ASR

执行主工作区和独立适配器测试；构建 arm64-v8a 与 x86_64；保留 API28/35/36 两轮输入、语音、隐私、备份与真实服务退出检查。新增明确选中 VoiceKey、加载其真实模型并完成 JNI 解码、预览和确认插入的用例。

分别记录状态机单测、合成模型探针、JNI、AudioRecord/Editable、真实 IME 和自然人声/ARM 真机证据。无合法测试模型或设备的项目写 BLOCKED/NOT RUN，不使用假识别文字，不降低断言，不删失败。所有公开测试资料仅用合成或明确可公开材料，真实用户录音默认留在本机。

### 5. 交付试用包并安排公平对比

对通过当前阶段门禁的包，提供固定下载来源、commit、APK SHA256、模型准备步骤、麦克风权限、切换/断网/取消/重录方法、开发签名与升级备份限制。避免每次随意换签名导致不能覆盖安装，不要求用户卸载唯一未备份的数据。

准备 S25 的同机同语料 A/B 测试，分别记录模型准备/校验、冷/热加载、首字、停止到定稿、CER、峰值内存、CPU、能耗和失败样例；报告 P50/P95 及样本数。顺序重放相同音频，避免并行争抢资源。不要把 RTF 当端到端延迟、把 100%-CER 当准确率，或先验宣称 VoiceKey 一定更快。结果决定推荐引擎，未经证据不改默认。

## 五、工程操作与工作方式

只在获授权的仓库、云端工作目录和隔离 CI 操作；未经单独允许不接管我的电脑、手机、服务器或 DNS。保留现有公开状态、MSIME/VoiceKey 固定来源、原数据和隐私边界。按 AGENTS.md 执行；不要在个人设备运行会清空测试环境的 CI 脚本。

复用 PR #71 并按需拆分可独立验证的切片；更新 Issue #72 的真实进度。先检查 GitHub 当前变化，禁止强推。CI/审查未满足前不强行合并；安全切片是否可合并和能否宣称真机交付分开判断。

在当前工作会话可用的执行范围内连续推进，不为普通文件修改、下一项测试或一般技术选择反复停下来让我说“继续”。遇到单点限制记录原因并推进其他任务；不要用定时任务代替研发，不承诺无人执行的后台工作。

完成一个切片后，将来源提交、实际修改、命令/退出码、CI/产物、覆盖边界、失败及下一项任务写回 docs/STATUS.md、docs/VOICEKEY_ACCEPTANCE.md、PR/Issue。不能只汇报“方案已入库”。当前可执行任务耗尽时，交付明确成果和唯一的集中阻塞清单。

现在开始：重新核验分支与 CI，阅读上述新文档，然后先修复 startVoice() 的模型选择入口并运行对应回归，再继续后续工程。
