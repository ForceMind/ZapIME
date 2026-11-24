# ZapIME（闪电输入法）｜持续研发接管

**2026-11-24 A3 接手入口：** [账号登录与自动加密同步实施计划及实测](ANDROID_ACCOUNT_SYNC_IMPLEMENTATION.md)。主机 OIDC、Go 签名和隔离 API28 测试 APK 实际回跳通过。接续验证 Go 账号接口、设备批准与领取，再接主 App。A2 草稿 PR #125 保留已通过的合成双端服务证据。

更新：2026-11-19（America/Los_Angeles）。这是已有工程的延续：实际读取仓库、修改代码、测试和提交，不重建项目，不每完成一步就等待“继续”。[前次交接原文](WORK_HANDOFF_PRE_INTEGRATION_20261108.md)作为历史保留，旧待验状态不能覆盖本页。

## 先核对实时仓库

- 仓库：[ForceMind/ZapIME](https://github.com/ForceMind/ZapIME)，Public，默认 `main`。
- 本轮已合并 [#102](https://github.com/ForceMind/ZapIME/pull/102)，merge `88112f48464ecec9c361ba03443cc7081feb6511`，tree `2c73b5abe92214ad97eb0104f11911fa6a9d3cb6`；这只是检查点，接续先 fetch 并核对最新 main、open PR、Issue、review、CI 与 Release。
- #103 已保存同源码完整验收与首轮 Launcher ANR 原件，主线检查点为 `a199d5658f1e071059b4c4b9bd2db02c381c188f`。
- [#104](https://github.com/ForceMind/ZapIME/pull/104) 已合并到 main：`8d484bb79f481fb047e4fc18fd6b8cb7432ecd4c`，来源 head `16c7d89ff7faa6c3b4979074ed9301cf25be9544`，八条固定源工作流最终全成功；原下载/夹具/触摸/ADB失败分别保留。只读 JNI/worker/40条分页已完成，**不要再从旧草稿重复实现**。
- [#107](https://github.com/ForceMind/ZapIME/pull/107) Go1.27.4安全修复和[#106](https://github.com/ForceMind/ZapIME/pull/106)/[#111](https://github.com/ForceMind/ZapIME/pull/111) Android CI诊断现均已合入；[#112](https://github.com/ForceMind/ZapIME/pull/112) 双固定语音夹具、[#110](https://github.com/ForceMind/ZapIME/pull/110) 首次发布安全基础、[#109](https://github.com/ForceMind/ZapIME/pull/109) 当前候选字恢复与[#114](https://github.com/ForceMind/ZapIME/pull/114) 本机语音精简预览也已合入主线。main 检查点 `f9a4f64d450e83172a7841eaf2649dc1edcd84b9`，后续仍先实际查询实时仓库。
- #87/#89/#91/#95/#96/#101 全部保留并由 #102 整合，不再从这些旧分支重复实现。#12/macOS、#40/Windows 仍为未通过系统验收的草稿，不能直接合并为完成。
- 保留任何新未提交工作或开放开发分支；不强推、不 reset 别人的工作，不操作用户电脑、手机、生产服务器或 DNS。

先读 [AGENTS](../AGENTS.md)、[STATUS](STATUS.md)、[开发计划](DEVELOPMENT_PLAN.md)、[路线图](ROADMAP.md)、[技术决策](DECISIONS.md)。涉及功能时再读[组字恢复契约](ADR_025_VERIFIED_CONFIGURATION_RESTART.md)、[候选评测](CANDIDATE_EVALUATION.md)、[双语音](VOICEKEY_INTEGRATION.md)、[语音验收](VOICEKEY_ACCEPTANCE.md)、[手动同步](ANDROID_MANUAL_SYNC.md)、[同步证据分页](SYNC_INVENTORY.md)。

```sh
git status --short
git fetch origin
git log -5 --oneline
python3 scripts/verify_upstream.py
```

## 已经完成的本轮交付

同编辑器配置重启使用独立物理单槽 worker 的有界新鲜证明；重复 restart 使旧 generation 失效、保留原 ticket/队列/800ms 预算，最多两次串行读取。普通强保留路径新增三态与生命周期守卫，setter 重入后不能写回旧状态。测试工具入口、生命周期诊断和冻结的 64 条新候选质量语料也已合入。

固定 head `52f812bc269f35ba636997b9c4664c2b83a22154`、实际 PR checkout `8cf2cae1fcc62827a5c508d38609c131ee6f009e` 与合并源树一致。八条 workflow SUCCESS；全量 Android `42517974761` attempt2 最终 6/6，候选 `42517974445` 6/6。API35 配置实验实际观察两次 restart，`你好sh → 你好shi → 你好是` 连续性、触摸、Editable 和清理通过。

首轮 VoiceKey API35 因系统 Launcher ANR 对话框遮挡 SAF 而失败，模型未解码；只定点重跑该作业，保留原失败 ZIP/SHA。独立审查及本机 Rust143/fmt/严格Clippy、ordinary/pending JVM 回归通过。完整范围与原件见[验收证据](evidence/android-stability-52f812b-20261108/README.md)。这不证明所有历史间歇问题、ARM/S25 或真实人声已验收。

## 最新重点（2026-11-19，固定依赖恢复与完整 CI 收口）

PR #122 / #123 的功能修复已整合至 main `c9207de739d6d1872502694826a69b8d29d4540e`。本轮八条工作流 attempt 1 全部 SUCCESS，验收固定 head `60beadee`、实际 checkout `95d93e4`、共同 tree `d4b8916`；精确 SHA、完整结果及开发包见 [最终证据](evidence/ci-recovery-20261119/README.md)。集成与后续文档提交仅为接续检查点，不能据此声称新 SHA 重新跑过本轮 8/8。原锁定 ORT 字节恢复和工具面板测试观察修复已交付，下方失败与待验状态按历史保留。

下一项继续 Issue #77：将真实 HTTPS 认证的完整终止拉取接入 #110 已有首次发布 API，复用预检、确认、冻结请求与 ACK 验证实际服务闭环；Android 明确选择、确认及进度另作独立切片。已有 JNI、只读状态页和安全队列不重做。Issue #38、#113 离线 LLM、S25 真机与正式签名稳定发布仍未完成。

## 前次重点（2026-11-19，PR #122 首轮）

起点 main `e2877a85ff2eb3b9eeba8eded83cdd014d5a0ebf` 已重新核验，旧CI全部有最终结果。Issue #120 的三个头文件和MIT许可证已从Intel同一commit恢复原字节，551824 bytes全部匹配旧锁；本切片将其纳入源码并始终核验后本地发布，无网络回退、版本或ABI变化。资源21项/下载28项及C/C++头文件检查通过，[证据](evidence/ort-source-recovery-20261119/README.md)保留六份旧404日志与完整来源。真实新Android门禁按本切片PR固定SHA查；独立Linux ORT1.28.2诊断归档仍不可取，不以其它构建代替，Issue120不关闭。

## 前次重点（2026-11-18）

**2026-11-18 main 检查点：`df471fe80f99659e96ed4e5efe2c05cc00dd3b2d`（非永久最新）。** 已合并 [#116](https://github.com/ForceMind/ZapIME/pull/116) 真实 Android 安装包中双离线语音清理与设置开关、[#118](https://github.com/ForceMind/ZapIME/pull/118) 24个SHA固定离线质量用例与实际 Java 门禁评估、[#119](https://github.com/ForceMind/ZapIME/pull/119) 混合汉字的 USB-C/VoiceKey 等技术标识不变保护。#119 来源归档运行了真实产品 Java/JVM 2+5+3 项检测，独立质量CI通过；系统 Android/Apple 等全部绿灯不能从前一版推断。用户真正要求的自然语音自动语义精简/模型仍未完成；#113开放，当前并未接入 LLM；默认本机保守清理不变。#116 的两条真实 Android/VoiceKey工作流通过，但API35独立配置实验失败仍归 Issue38。此前已完成#114不要重建。

[Issue #120](https://github.com/ForceMind/ZapIME/issues/120) 新阻塞：固定 ONNX Runtime v1.28.2 头文件下载 HTTP404 导致 VoiceKey focused 构建失败，先确认确切 URL、可达的可信发布来源及 SHA，不得用最新main替换头文件、假造校验或降低安全条件。保持原版本签名和构建来源可审计。

后续选一条独立可合并安全切片：优先解决真实资源与CI可复现、精确质量差错归因；随后经合法许可检查尝试离线模型推理，保证原文/精简/未来智能三态、用户确认才插入、数字否定专名不变与取消安全。同步 Issue77 仍需真实端到端HTTPS与 Android 明确授权；S25真机集中由用户实施而非远程触控。

## 历史重点（2026-11-16）

1. **不要重复实现 #109/#110/#112/#114。** #109 合并源固定八工作流成功；#114 合并后真实 API28/35/36 语音/候选与 iOS/Windows 的完整 CI 仍需追踪，历史失败保留。不要把源码/宿主或开发 APK 编译成功冒称三星 S25 真机通过。
2. 继续 [Issue #113](https://github.com/ForceMind/ZapIME/issues/113)：默认关闭的本机保守精简已实现，原文切换/确认为已有产品代码；下一步收集自然语音原文/精简配对、语义错误与关键实体保持率，之后审查许可清晰的离线文字重写小模型。参见[契约](VOICE_CLEANUP.md)。
3. 继续 [Issue #77](https://github.com/ForceMind/ZapIME/issues/77)：#110 只是无 UI/HTTP 的显式初始队列，真正 HTTPS 认证终止页、Android 确认、撤销/冲突/重复 ACK/墓碑和两端恢复验收均未交付。不能自动上传。
4. 完成设备所有者的[S25总验收单](S25_ACCEPTANCE_CHECKLIST.md)：不能远程操作真实手机或生产数据；新候选必须使用同一源码/签名/模型清单。

## 下一轮优先顺序

1. 从当前 main 继续开发；#104已完成同源码8条workflow验收并合入，不重新实现已存在的JNI/worker/本地同步页。记录原HTTP504、初轮Candidate API36和API35 ADB准备失败；后续重跑成功不是已有根因都已修复的证据。详情见[状态](STATUS.md)与[旧失败原件](evidence/android-sync-inventory-ci-20261108/README.md)。
2. 优先推进 [Issue #77](https://github.com/ForceMind/ZapIME/issues/77) 的恢复/导入记录显式首次发布：先拉取远端、重叠ID/唯一性/旧墓碑保护、用户确认、有界幂等操作及中断恢复；每次HTTP仍需持久stage和完整材料绑定，旧冻结请求不改写。不得用重存、改ID或重新上传普通下载绕过。不能把pending=0/远端修订视为备份确认。
3. 原候选API36第一次重启附近丢触摸，后续同源真实系统IME验收通过，但根因仍未从DOWN/UP/dispatch事件定位；Issue #38保留。候选 C1 继续按原34条与64条冻结集、缺失/重排伤害、九键/补全、资源与隐私回退推进，不把合成分数称用户准确率。
4. 无需手机的工作继续推进；手机只集中使用 [S25 总验收单](S25_ACCEPTANCE_CHECKLIST.md)。自然语音、ARM 延迟/RSS/能耗、长输入、隐私/取消、正式签名与可恢复升级分别待验。
5. 按路线推进配对、冲突、历史密钥与完整灾备、其他原生宿主、英语伴学。已有 Android 原生输入/主题/资料/双离线语音/显式手动同步不删除或重做。

## 交付与边界

用户已授权常规开发、测试、GitHub 提交/符合条件的合并及按实际范围更新文档和发布。独立切片用分支/PR；保留源码、编译、模拟器、手机和签名层级的区别。严格校验真实报告，不能放宽断言、删失败或用旧源码结果代填新源码。

Rust 固定 1.97.1，执行 `cargo test --workspace --locked`、`cargo fmt --all --check`、`cargo clippy --workspace --all-targets --locked -- -D warnings`；Android 用 JDK17、API36、build-tools35.0.0、NDK28.2.13676358。VoiceKey 独立锁文件，服务事务要真实 PostgreSQL/S3；SKIP 不等于通过。所有临时 CI 清资料/root/CA/防火墙操作只用于受保护隔离模拟器。

输入线程不等网络；候选增强默认开启且无可信上下文保留基础排序；X-ASR 默认、VoiceKey 显式可选；语音和上下文不默认落盘/上传。同步仅显式逐轮确认，不新增默认上传或后台轮询。

公开源码 [v0.1.0-alpha.1](https://github.com/ForceMind/ZapIME/releases/tag/v0.1.0-alpha.1) 固定 `026bfb8bf467c0d45193a50016e298c3dbb0ac01` 与三个原附件，不移动标签或覆盖文件。VoiceKey 作者允许整合的事实已记录，不反复询问；标准公开分发条款、第三方模型和候选训练来源声明仍按[许可审计](LICENSE_AUDIT.md)分别收口。源码/开发构建成功不等于通用稳定二进制发布条件完成。
