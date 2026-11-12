# ZapIME（闪电输入法）｜持续研发接管

更新：2026-11-12（America/Los_Angeles）。这是已有工程的延续：实际读取仓库、修改代码、测试和提交，不重建项目，不每完成一步就等待“继续”。[前次交接原文](WORK_HANDOFF_PRE_INTEGRATION_20261108.md)作为历史保留，旧待验状态不能覆盖本页。

## 先核对实时仓库

- 仓库：[ForceMind/ZapIME](https://github.com/ForceMind/ZapIME)，Public，默认 `main`。
- 本轮已合并 [#102](https://github.com/ForceMind/ZapIME/pull/102)，merge `88112f48464ecec9c361ba03443cc7081feb6511`，tree `2c73b5abe92214ad97eb0104f11911fa6a9d3cb6`；这只是检查点，接续先 fetch 并核对最新 main、open PR、Issue、review、CI 与 Release。
- #103 已保存同源码完整验收与首轮 Launcher ANR 原件，主线检查点为 `a199d5658f1e071059b4c4b9bd2db02c381c188f`。
- [#104](https://github.com/ForceMind/ZapIME/pull/104) 已合并到 main：`8d484bb79f481fb047e4fc18fd6b8cb7432ecd4c`，来源 head `16c7d89ff7faa6c3b4979074ed9301cf25be9544`，八条固定源工作流最终全成功；原下载/夹具/触摸/ADB失败分别保留。只读 JNI/worker/40条分页已完成，**不要再从旧草稿重复实现**。
- [#107](https://github.com/ForceMind/ZapIME/pull/107) 已合并Go1.27.4安全修复（`6009e4b24a42208564e6d66b77edb059fca02fa5`）；[#106](https://github.com/ForceMind/ZapIME/pull/106) 为独立Android CI诊断候选，检查其最新CI/合并状态，不假定已验收。
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
