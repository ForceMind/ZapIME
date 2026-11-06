# ZapIME 项目执行状态

更新日期：2026-11-06。本轮是**方案、目标、计划和工作会话交接更新**，没有修改产品代码或进行新一轮真机测试。

## 当前执行主线

近期交付目标：Android 上可给用户和 VoiceKey 作者试用的双离线引擎 Alpha。保留 X-ASR 为默认，将 VoiceKey 微信离线作为可选方案。完整四端输入、用户数据、自建 E2EE 同步、低频 R2 和英语伴学目标不取消，但不阻塞首个 Android 双引擎交付。

- 代码核验基线：`e24f1bd21ecd4fca99d909a972116e5a36e5f6c2`。
- 工作分支：`feat/voicekey-engine-import`，PR #71 open/draft/未合并；Issue #72 open。
- main 核验值：`5621ab0e81780ca58c6cf74e97c93928a10f981a`。
- 本轮文档提交会位于代码基线之后，后续执行者必须重新读取实时 head，不硬编码“latest”。

## 相比上次汇报的新事实

同一代码提交的六条 GitHub Actions 已全部 success。Android run `42199407235` 的 arm64-v8a/x86_64 构建以及 API28/35/36 既有回归均成功。VoiceKey 原 ARM `vdotq_s32` 编译失败的修复已通过 Android 构建；当前使用稳定 NEON 本地适配，不再要求旧 dotprod CPU 门禁。

**这不代表 VoiceKey 模型驱动的真实识别、只装 VoiceKey 的完整路径、三星 S25 或自然语音质量已经通过。** 适配器默认云代码隔离；工作流绿灯只覆盖实际执行的用例。详细 run/job/产物元数据、覆盖界限和未完成项目见 [VOICEKEY_ACCEPTANCE](VOICEKEY_ACCEPTANCE.md)。

已产生 Android 开发 artifact `13494970099`，不是“还在排队”状态。本轮只核验 GitHub 元数据，未下载字节或独立计算其 SHA，因此仍需复核内容与专项识别后更新新试用包说明；不替换旧 `ANDROID_TRYOUT.md`，不宣称已发正式版。

## 接管后的首批工作

1. 修复 `ZapInputMethodService.startVoice()` 对 X-ASR 模型的硬编码准备检查，加入仅装 VoiceKey 等五类入口回归。
2. 收口模型信任锚、缓存替换/释放、PCM/空结果、partial 稳定、取消/超时与 JNI 生命周期；具体风险和契约见 [整合方案](VOICEKEY_INTEGRATION.md)。
3. 新增真正选择 VoiceKey 的模型解码、JNI、录音、输入确认与退出证据；保留原 X-ASR 及全部基线回归。独立适配器工作流要严格验证格式/依赖，不靠软失败过关。
4. 核验产物并交付可供真机验收的候选包、来源/校验/安装/升级说明及一次性的 S25 验收单。缺真机时先完成所有非真机工作。

## 全项目范围保持不变

Android 已有原生键盘、九键/全键、主题和系统反馈、本机资料面板、候选置顶以及备份相关实现。其历史证据继续保留。macOS/iOS 有原生宿主构建和桥接基线，Windows TSF 仍需按 `WINDOWS_ACCEPTANCE_REPORT.md` 处理真实系统注册验收；通用构建成功不自动关闭这些问题。

自建 Go/PostgreSQL/R2 服务已有独立工程与测试；Android 设备连接不是完整 E2EE 配对/资料同步。四端资料互通、服务器真实部署、正式签名和英语伴学实际翻译/UI均不能视为本轮完成。

## 当前文档入口和历史保留

- [开发计划与目标](DEVELOPMENT_PLAN.md)：近期 V0–V5 和长期四端路线。
- [VoiceKey v2 技术整合](VOICEKEY_INTEGRATION.md)：实际代码路径、关键风险和验收契约。
- [验收矩阵与证据](VOICEKEY_ACCEPTANCE.md)：已核验与未执行分开。
- [ADR-020](ADR_020_VOICEKEY_DELIVERY.md)：补充/修正 ADR-019 的实施与交付边界。
- [完整工作接管提示词](WORK_HANDOFF_PROMPT.md)：接续研发，不重做计划。
- [原状态全文](STATUS_HISTORY_20261106.md)：按原始 blob 完整保留截至本次更新前的状态、成功/失败和旧引用。该文件是历史，不覆盖本文的新状态。

本轮未合并 PR #71、未修改 main、未升级上游、未发布新 Release，也未操作用户电脑、手机或服务器。源码/构建/专项识别/真机/发布仍分别验收。
