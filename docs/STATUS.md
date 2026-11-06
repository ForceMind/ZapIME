# ZapIME 项目执行状态

更新时间：2026-11-06 03:35 PST（America/Los_Angeles；11:35 UTC）。本轮已经实施产品代码、测试与 CI，不是只更新计划。累计汇报次数和上次间隔没有可靠连续记录，不补造。

## 实施后的专项补充（03:54 PST）

实现 `5bbfe310e716762b36cdb0cf28b09ad5a2e1818c` 已提交 PR #71 并同步 Issue #72。

- 原生 ARM/x86 run `42211989370` 两端各20项实际执行、数值/极值/尾维度对照、严格fmt/Clippy/locked及release微内核诊断全部通过。云端内核诊断不等于手机或语音端到端性能。
- 真实模型 CPU run `42211989430`：x86 固定官方模型、生产 Java 导入、断网 Rust 解码通过。固定合成样本125490个PCM样本，speech-v1 CER=15/22（约68.18%）；保留全部错误与六轮重复，不把重复算新增语料，不推荐第二引擎。ARM两次均因rustfmt安装冲突失败，未执行模型，原始失败及实际下载metadata校验已归档。下一切片显式安装固定Rust组件，未软化测试。
- Android run `42211989441` 已通过双ABI真实构建、新打包硬门禁及API28现有完整回归；API35/36及VoiceKey-only专项仍在执行，尚未取得新APK字节校验或模型JNI证据。该包是中间实验基线。
- 独立安全复核新增确认权限缺口：本切片在结果回调、确认点击与异步上下文完成时复查当前RECORD_AUDIO权限；增加生产查询helper驱动的真实Editable权限状态测试。权限变化为ContextWrapper模拟，不冒称系统撤权/进程退出验收。最终候选必须包含此修复。本切片Android host31项再通过，产品/23条事务探针Java以API36编译通过；23条Android实际执行尚待新CI。
- 为保留正在执行的完整验收及失败证据，Android后续提交改为排队而非取消当前run。仍需每次最终候选对应的新完整检查通过。

以下表格保留此前03:35本地检查快照；新结果以上述精确run和原始报告为准。源码可立即交本地Codex接续；Alpha下载/签名/试用说明仍待最终包。

## 当前主线与 GitHub

近期目标仍是可给用户和 VoiceKey 作者试用的 Android 双离线引擎 Alpha；X-ASR 默认，VoiceKey 可选。长期四端输入、个人资料、自建端到端加密同步、低频 R2、恢复与英语伴学保持原计划。

- PUBLIC 仓库：ForceMind/ZapIME；分支 `feat/voicekey-engine-import`；复用 PR #71、Issue #72。未强推、未改可见性、未合并或发稳定版。
- main 最新核验：`5621ab0e81780ca58c6cf74e97c93928a10f981a`。
- 本轮已保存审计代码：`20f68dcc0e11c14ae69f6db5692567ea72dfb1a2`。本页随下一实际实现切片提交，Git commit 是其精确源码标识。
- 固定上游：VoiceKey `3ba017826eef7fac405198510b03859eb5b6c8c4`，MSIME `97d652a49cabb0887355b84186af2aa9032d3814`；未改脏 vendor。

## 本轮实际变更

1. `startVoice()` 固定所选引擎后在 VoiceWorker 检查其独立模型管理器，传递不可变会话到录音。只装 VoiceKey 不再被 X-ASR 准备条件拦截；大文件验证不进入 UI/按键线程。无新 partial 保留已有预览，停止后仍需原事务确认，最多插入一次。
2. Rust 改为有界阻塞解码 worker 和协作取消；实际线程退出前保留物理容量，超时不会被解释为推理已停止。满队列显式失败；有效 count 前缀累积非零证据；空/全零不尾部生成文字。缓存身份感知原子替换，导入验证与运行缓存隔离，取消/关闭/内存及闲置释放明确所有权。
3. 独立复核发现并修复旧 instrumentation 反射构造不匹配，以及解析异常污染缓存锁问题。模型解析移到缓存锁外并捕获 panic，增加短 XNET 与真实 Fbank 边界测试。Fbank 保留上游 snip-edges：不足一跳长的尾样本不自动补零；队列完整接收不代表特征补帧。
4. 新固定信任锚 `third_party/voicekey-model-lock.json` 与生成 Java 白名单。官方原包/内层/所有成员固定尺寸和 SHA256，ZIP 解析前先验证原包；拒绝未知/重复/穿越/符号链接/嵌套/超量，取消或 IO 失败保留旧有效模型，同版本不替换文件。导入与恢复在独立 worker。
5. 适配器依赖锁、独立许可清单/实际文本、8 JNI 符号、双 ABI/16KiB/动态依赖与不含第三方模型的 APK 检查已加入硬门禁。原作者整合许可按用户确认保留，权重再分发许可未确立，不随公开 APK 或测试产物分发。
6. 新增真实 CPU 模型探针（原生 x86_64/ARM runner）、真实 JNI 无模型边界探针，以及专用全新 API28/35/36 VoiceKey-only 官方模型 SAF/JNI/Editable/系统 IME 离线验收。已有 X-ASR、键盘、资料、隐私、备份和真实退出门禁保留，没有软化失败。

## 已实际运行的证据

| 范围 | 本轮结果及边界 |
| --- | --- |
| 引擎选择/partial | JVM 12 cases PASS，覆盖仅 X-ASR、仅 VoiceKey、都有、均无、所选损坏及无 fallback；不是模型推理 |
| 模型安装器 | 生产 Java 真实 IO fixture 17 类 PASS。ENOSPC 为写失败注入，中断为目录状态模拟，不冒称真实磁盘耗尽/杀进程 |
| Rust 适配器 | 20 tests PASS；严格格式/锁依赖检查；Android ARM 目标 cargo check PASS。目标编译不是 ARM 设备执行 |
| 产品 Java/测试 Java | API36 android.jar + Java compiler source8/target8 编译 PASS；不是 APK 安装或系统 IME 运行 |
| Python 工程边界 | Android host 原25项及新5项专项元数据测试，CPU探针19项通过；不是实际 JNI |
| 根 Rust 工程 | workspace locked test、fmt、strict Clippy PASS；详细日志未包含模型权重 |
| 官方模型来源 | 独立 CI run `42209579465` SUCCESS；artifact `13498970123` 实际下载 ZIP 1335B / SHA256 `3edac8fcfbbea036d743d99b48803a63b60ccf57846267aea9811db0f91650fd`。模型原包 SHA256 `b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863`；只证明来源完整性，不是推理 |
| 旧基线 APK | run `42199407235` / artifact `13494970099` 实际下载；双 ABI、签名及16KiB对齐、JNI/署名独立复核。此包缺少新门禁所需 releaseCache/完整适配器依赖许可，因此不冒充新候选 |

原始证据：[本轮目录](evidence/voicekey-runtime-20261106/)。官方来源本地曾返回 195B HTML，保留失败观察；隔离 CI 后取得可信原包，不能把本地失败删掉或把元数据当识别成功。

## 仍待真实执行与交付

- 当前实现提交的新 Android 双 ABI APK 和保留回归：等待新 CI 构建/运行后记录准确 SHA，不套用 e24f1bd 六条绿灯。
- VoiceKey 真实合规模型 CPU、原生 ARM 数值/性能、实际 JNI、SAF 和只装 VoiceKey 的系统 IME：新专项已接硬门禁，当前未取得新运行结果，不称 PASS。
- 三星 S25 / ARM 手机、自然语音 CER、端到端延迟、CPU/能耗/温度：NOT RUN。没有用户设备访问权限或本环境真机。
- 模型再分发权利、正式签名与稳定发布：未满足。仍按用户显式取得官方模型；开发签名覆盖升级前须备份。

下一步是查看新 CI 真实失败并修复，实际下载/校验新候选 APK，更新试用说明及固定源码/校验值。一次性手机验收见 [S25 集中验收单](S25_DUAL_VOICE_CHECKLIST.md)，不要求用户现在执行零散步骤。可立即交给本地 Codex 继续源码开发，但不能将该移交等同于 Alpha 验收完成。

## 长期范围与历史

原 Android 九键/全键、主题/触感、资料面板、候选置顶与备份保留。其他三端原生验收、完整四端同步/配对/恢复、服务器实际部署与英语伴学翻译/UI仍未完成。本轮未操作用户电脑、手机、服务器或 DNS。

[开发计划](DEVELOPMENT_PLAN.md) · [技术整合](VOICEKEY_INTEGRATION.md) · [验收与历史证据](VOICEKEY_ACCEPTANCE.md) · [ADR-020](ADR_020_VOICEKEY_DELIVERY.md) · [接管指令](WORK_HANDOFF_PROMPT.md) · [此前状态原文](STATUS_HISTORY_20261106.md)。
