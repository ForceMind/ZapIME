# VoiceKey 双引擎验收矩阵与交接快照

核验日期：2026-11-06。第 1–6 节保留接管时固定源码的历史快照，其“本轮”指当时仅更新文档及读取元数据的工作。**本次实际开发、旧产物下载核验及新源码主机结果见第 7 节；新产品 CI/模型推理/真机结果仍须分别记录。**

## 1. 源码与分支

- 仓库：`ForceMind/ZapIME`，main 基线 `5621ab0e81780ca58c6cf74e97c93928a10f981a`。
- 接续分支：`feat/voicekey-engine-import`；PR #71 为 open / draft / 未合并，Issue #72 为 open。
- 本轮核验的产品代码：`e24f1bd21ecd4fca99d909a972116e5a36e5f6c2`，tree `45e8ce851fb3bf0e00ff6c1aa71f277bca12dbc7`。本轮新增文档提交位于其后，不能把旧 CI 冒称为新提交已运行。
- VoiceKey：`J3n5en/voicekey@3ba017826eef7fac405198510b03859eb5b6c8c4`；MSIME 保持既有锁定，不随本次文档变更升级。

## 2. 六条 CI 的已核验结论

| 工作流 | Run ID | GitHub 结论 | 能证明什么 |
| --- | --- | --- | --- |
| VoiceKey adapter (isolated) | 42199407194 | success | 此工作流实际覆盖范围内的适配器构建/单测；不代表 Android 模型识别 |
| Android native input acceptance | 42199407235 | success | 双 ABI 包构建、API28/35/36 现有用例；不自动覆盖第二引擎 |
| ZapIME development checks | 42199407181 | success | 当前通用检查覆盖范围，不是四端系统安装验收 |
| Offline speech CPU probe | 42199407197 | success | 既有离线模型 CPU 探针，不是 VoiceKey 真人准确率 |
| Windows native TSF development | 42199407188 | success | 当前 TSF 开发检查，不等于 Windows 系统 profile 问题已解决 |
| Independent ciphertext sync service | 42199407202 | success | 当前服务测试，不等于已部署用户服务器或四端同步完成 |

工作流链接格式为 `https://github.com/ForceMind/ZapIME/actions/runs/<Run ID>`。原始观察整理在 [机器可读快照](evidence/voicekey-handoff-20261106/ci-snapshot.json)。若步骤存在 continue-on-error，工作流 success 不能证明该步骤严格通过；后续须检查原始步骤并修正门禁。

Android job `127830928384` 中 arm64-v8a、x86_64 构建及 API28 smoke 均 success；API36 job `127835410023` 和 API35 job `127835410061` 均 success。不能将原 X-ASR 的模型夹具、静音录音或既有界面测试算成 VoiceKey 的实际解码闭环。

## 3. 构建产物信息及边界

GitHub 返回 `ZapIME-Android-development`：artifact `13494970099`，大小 `234930585` 字节，摘要 `sha256:0669c42660866c3ed41ff2126810f42f15453bfe30f30f84ae4f203cc345271e`，查询时未过期，标记到期时间为 `2027-02-04T10:01:15Z`。

来源为 run `42199407235` / commit `e24f1bd...`。这是 **GitHub 元数据，不是本轮下载后计算的 SHA**，更不是 ARM64 APK 本身的哈希。下一会话须取得实际 ZIP，核对大小/摘要、成员清单、APK 自身 SHA、JNI 库/符号/对齐/许可材料和签名后才能给出新的固定试用说明。

本候选已有构建产物，但尚不称为经 VoiceKey 专项与 S25 验收的双引擎 Alpha。不替换 `ANDROID_TRYOUT.md` 的旧已验收下载说明，不把历史 636 字节失败产物当 APK，也不擅自生成正式 Release。

## 4. 已确认问题与待验证风险

**VK-01（已静态确认，优先修复）：** `ZapInputMethodService.startVoice()` 仍依赖 `VoiceModels.installed/recoverable`，未按所选引擎检查模型；仅装 VoiceKey 的情况可能无法进入录音。这次读取了基线源码，未进行动态复现。

**以下是待审计/测试的风险，不计为已修复或已验证：** JNI 非零 PCM 标记与空结果策略；新 partial 缺席时预览是否被擦除；finish 超时/abort 后阻塞推理是否真正退出；相同模型路径替换后的缓存失效；导入阶段模型缓存污染；原包固定 SHA256 信任锚；有界队列满时音频完整性；异常跨 JNI；稳定 NEON 与标量的 ARM 数值/性能；格式检查软失败与适配器依赖未锁定。

## 5. 执行清单（复选框只代表明确的阶段事实）

### 已有工程基础

- [x] 完整 VoiceKey 源码固定为子模块，并保留来源。
- [x] 离线适配器及 Android JNI/设置/录音分流候选代码已提交。
- [x] 稳定 NEON 修复后，双 ABI Android 构建成功。
- [x] 同一产品源码的六条工作流及 API28/35/36 现有回归成功。
- [ ] 原始产物本轮下载、逐文件哈希/签名/对齐核验完成。

### V1/V2 非真机门禁

- [ ] VK-01 修复，并覆盖仅 X-ASR/仅 VoiceKey/双模型/无模型/损坏模型。
- [ ] 选中引擎和模型版本在会话内一致，切换/重启/配置变化无串场。
- [ ] 固定可信模型目录/包清单、尺寸/SHA/来源/署名和升级策略完成。
- [ ] 错包、未知/重复成员、路径穿越、超大/嵌套包、符号链接、取消、存储不足、中断恢复和旧模型保留测试通过。
- [ ] 无样本/全零/±1非零/尾块/超限、partial/final 顺序与错误传播测试通过。
- [ ] cancel/timeout/close/失焦/移动光标/隐藏/权限撤销后无上屏、无孤儿任务和无限内存增长。
- [ ] VoiceKey JNI 真实加载与模型解码，结果经原确认事务进入真实 Editable；证据标明所选引擎和模型。
- [ ] VoiceKey 的系统 IME 入口和录音路径独立验证，原 X-ASR 与键盘/资料/备份回归保留。
- [ ] ARM 数值对照与 x86 数值测试分别通过；不能只验证宿主架构。
- [ ] 适配器依赖锁、严格格式/测试门禁、打包来源与动态依赖白名单完成。
- [ ] 生成可重建、可校验、明确标注实验状态的试用包和一份集中真机验收单。

### V3 真机与交付门禁

- [ ] S25 真实安装、双引擎独立模型使用、断网、自然麦克风及确认插入通过。
- [ ] 权限、蓝牙、前后台、输入框/光标、取消重录、长句与隐私字段通过。
- [ ] 同机同语料 X-ASR/VoiceKey 的冷/热首字、定稿、CER、资源、能耗和失败样例报告完成。
- [ ] 开发签名升级兼容或备份迁移步骤明确；不要求用户删除唯一未备份资料。
- [ ] 对外二进制对应源码、作者授权记录与第三方模型/依赖材料齐备。

## 6. 证据格式与失败处理

每轮记录 commit/tree、平台/ABI/API/机型、模型版本/SHA、引擎 ID、命令/退出码、覆盖用例、实际结果、产物摘要、限制和失败。原始失败保留，不通过改变样本或降低门禁消除失败。

测试分为：状态机/合成单元测试、离线模型探针、真实 JNI、Android 录音与编辑器、真机自然语音、发布签名。缺资源时标 BLOCKED 或 NOT RUN，不填 PASS。没有真实设备时完成全部可在云端执行的事项，交付候选包与操作单，真机项继续保持未勾选。


## 7. 2026-11-06 实际硬化切片（保留历史记录）

本节为 PR #71 接管后的实际修改与本地结果，集成提交由 [STATUS](STATUS.md) 记录。旧复选框保留当时事实；主机 PASS 不能升级为新 APK 或模型识别 PASS。

### 实际旧产物核验

已完整下载 run `42199407235` / artifact `13494970099`，实算 ZIP 为 234930585 字节、SHA256 `0669c42660866c3ed41ff2126810f42f15453bfe30f30f84ae4f203cc345271e`，与公布值一致。两个 APK 的摘要、原生库、7 个旧 JNI 导出、动态依赖、manifest、开发签名和 `zipalign -c -P 16 4` 已实际核对。原始结果见 [基线核验](evidence/voicekey-runtime-20261106/baseline-README.md)与[机器记录](evidence/voicekey-runtime-20261106/baseline-package-verification.json)。

该基线仍有 VoiceKey-only 入口问题及适配器依赖许可漏包，不满足新增 `releaseCache` 和独立许可清单门禁，不能充当本轮新候选。字节/签名检查没有执行模型或 ARM 代码。

### 新源码及主机验证

| 层级 | 实际执行 | 结果与边界 |
| --- | --- | --- |
| 路由/预览 | 按所选引擎独立准备、每次录音冻结选择、无新 partial 保留预览；JVM 12 例 | PASS；包含仅 X-ASR/仅 VoiceKey/双模型/无模型/损坏所选模型，不是模型解码 |
| 模型存储 | 固定目录与原包/成员 SHA、暂存发布恢复；JVM 17 例 | PASS；合成非模型内容，不是实际 ENOSPC、杀进程、SAF 或手机 |
| 适配器 | 物理 worker 持容量至真正退出，协作取消/背压/PCM/缓存/JNI 边界；20 单测 | PASS；真实固定 Fbank 分块及小型格式探针不等于正式模型识别 |
| 工具链 | 严格格式、独立锁定树、native x86 与 AArch64 Android all-targets clippy | PASS；AArch64 Android cargo check PASS，不是链接后 ELF/JNI 运行 |
| Android Java | API36 产品和 voice-tests 编译 | PASS；不是安装或 instrumentation 执行 |
| Python | Android host suite 30 例，含新增打包 8 例 | PASS；纯主机工具门禁 |
| 许可 | 双 Android target 各 32 个非 dev 包实际许可收集与清单校验 | PASS；[本地证据](evidence/voicekey-runtime-20261106/packaging-local.json)保留较早 25 例执行及该次源哈希，后续总套件扩展为 30，不混为同一次运行 |

本环境初次默认 PATH 无 javac 返回 127；指定 `JAVAC="$PWD/target/toolchains/javac"` 后路由/存储脚本实际复跑通过。使用 OpenJDK 21.0.13 的 `-source 8 -target 8`，保留 bootstrap/obsolete 警告，不伪称 `--release 8`。

适配器不再依赖 Tokio/async-trait。close/超时不宣称强制杀死计算，未退出 worker 仍保留物理解码容量。Fbank 保留 400 样本窗/160 步长 snip-edges，未声称不足一窗的末尾已 flush。x86 release 合成 kernel 计时只是诊断，不是手机或端到端延迟，不据此推荐引擎。

### 模型信任与未完成验收

官方来源完整性元数据审计 [run 42209579465](https://github.com/ForceMind/ZapIME/actions/runs/42209579465) 已通过。审查后的 [模型锁](../third_party/voicekey-model-lock.json) 固定 `wechat-offline-20260710220526250`、原包 102920639 字节及 SHA256 `b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863`，并固定全部包/成员尺寸和 SHA；Android 目录由该锁生成。此前本地 CDN 返回 HTML 的失败记录保留。该审计只有来源完整性元数据，没有推理/JNI/手机或模型再分发许可结论。

新模型 CPU、Android VoiceKey 专项 JNI/AudioRecord/Editable、真实系统 IME 及 native ARM 数值/性能 workflow 已增加，**PENDING / NOT RUN，只有实际 CI 结果可改为 PASS**。不能用原 X-ASR、旧 APK 或 x86 单测替代。S25、自然语音、双引擎同机同料质量/资源/能耗与 P50/P95 报告仍未完成；集中操作见 [S25 清单](S25_DUAL_VOICE_CHECKLIST.md)。本地探针构建/输入边界与未推理原因见 [独立记录](evidence/voicekey-runtime-20261106/cpu-probe-build-input-check.json)。

[适配器 LICENSE](../integrations/voicekey/LICENSE) 记录用户确认源码整合及其边界；ZapIME 自有适配/修改为 GPL-3.0-only，不替 VoiceKey 宣称 GPL。标准公开分发条款仍须有记录；第三方权重再分发权利未建立，产品及公开测试 APK/产物不捆绑 VoiceKey 权重，不启用提取凭据或非公开云协议。
