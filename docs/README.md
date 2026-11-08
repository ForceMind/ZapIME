# 文档导航

先看[项目首页](../README.md)、[当前状态](STATUS.md)和[路线图](ROADMAP.md)。状态记录中的固定提交是历史证据身份，接续时还要查询实时 GitHub。

| 目的 | 文档 |
| --- | --- |
| 获取、构建与贡献 | [构建](BUILD.md) · [贡献](../CONTRIBUTING.md) · [工程约定](../AGENTS.md) |
| 本次版本与后续发布 | [Alpha.1 说明](releases/v0.1.0-alpha.1.md) · [公开源码原件](evidence/source-alpha-publication-20261107/README.md) · [发布故障回归](evidence/release-draft-recovery-20261107/README.md) · [变更记录](../CHANGELOG.md) · [发布流程](RELEASING.md) |
| 持续开发 | [接管入口](WORK_HANDOFF_PROMPT.md) · [开发计划](DEVELOPMENT_PLAN.md) · [技术决策](DECISIONS.md) · [架构](ARCHITECTURE.md) |
| 中文候选 | [契约](CANDIDATE_RANKING.md) · [评测](CANDIDATE_EVALUATION.md) · [固定候选试用](CANDIDATE_RANKING_TRYOUT.md) |
| 双离线语音 | [整合方案](VOICEKEY_INTEGRATION.md) · [验收矩阵](VOICEKEY_ACCEPTANCE.md) · [试用](DUAL_VOICE_ALPHA_TRYOUT.md) · [S25 集中验收](S25_ACCEPTANCE_CHECKLIST.md) · [语音子单](S25_DUAL_VOICE_CHECKLIST.md) |
| 本地资料与同步 | [Android 手动同步](ANDROID_MANUAL_SYNC.md) · [同步协议](SYNC_PROTOCOL.md) · [加密协议](CRYPTO_PROTOCOL.md) · [缓存并发契约](OBJECT_CACHE_CONCURRENCY.md) · [真实事务/灾备原件](evidence/cache-fill-ci-20261108/README.md) · [服务部署](../services/sync/README.md) |
| 其他原生平台 | [Apple 宿主](APPLE_NATIVE.md) · [Windows 构建](WINDOWS_NATIVE.md) · [Windows 失败记录](WINDOWS_ACCEPTANCE_REPORT.md) |
| 隐私、许可与来源 | [安全](../SECURITY.md) · [许可审计](LICENSE_AUDIT.md) · [NOTICE](../third_party/NOTICE.txt) · [候选来源](../integrations/candidate/SOURCE.md) |

原始结果在 `docs/evidence/`，历史接管快照在 `docs/history/`。更正状态请追加有提交和运行身份的新记录，保留旧失败与原始字节。

当前系统失败与新切片先看 [STATUS 当前检查点](STATUS.md)；主机质量成功、编译成功和清理成功不能代填系统输入或恢复 UI。手机操作统一由 [S25 总验收单](S25_ACCEPTANCE_CHECKLIST.md)组织，语音子单只是其中一部分。
