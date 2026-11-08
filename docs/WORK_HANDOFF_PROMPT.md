# ZapIME（闪电输入法）｜源码 Alpha 之后的持续研发接管

更新：2026-11-07。此文件取代旧分支交接；上一版原字节保存在[历史快照](history/WORK_HANDOFF_PROMPT_PRE_SOURCE_ALPHA_20261107.md)。

## 任务与接续入口

继续已有工程，实际修改代码、测试、修复和提交 GitHub，不从零重建，不只给计划，不需要用户反复发送“继续”。仓库 [ForceMind/ZapIME](https://github.com/ForceMind/ZapIME) 保持 Public。默认从实时 `main` 接续，源码版本入口为 `v0.1.0-alpha.1`；先读取最新 PR、CI、Release，不能把本文件当作实时 API。

本轮整合分支为 `release/open-source-alpha`，功能基线是 `5d976f578a2b11d14b9050ff0ec01e5d18d9e538`，此前 PR #71/#73/#74/#75/#78/#79/#80/#81 的实际实现由该基线承接。若当前 main 尚未包含该基线，先查整合 PR 的真实状态，禁止从旧 main 或早期语音分支重新做已经完成的工作。保留未提交内容，不强推或 reset 已有工作。

```sh
git clone --recurse-submodules https://github.com/ForceMind/ZapIME.git
cd ZapIME
git status --short
git fetch origin
git log -5 --oneline
python3 scripts/verify_upstream.py
```

先读 [AGENTS](../AGENTS.md)、[STATUS](STATUS.md)、[开发计划](DEVELOPMENT_PLAN.md)、[路线图](ROADMAP.md)、[技术决策](DECISIONS.md)、[源码 Alpha 说明](releases/v0.1.0-alpha.1.md)。涉及模块再读 [候选契约](CANDIDATE_RANKING.md)、[评测](CANDIDATE_EVALUATION.md)、[双语音整合](VOICEKEY_INTEGRATION.md)、[语音验收](VOICEKEY_ACCEPTANCE.md)、[手动同步](ANDROID_MANUAL_SYNC.md)。

## 已完成工作与证据

- Android 原生全键/九键、个人规则、主题、触感、资料面板、备份、双离线语音及显式手动密文同步已有实现，不能删掉后重新做。
- 默认离线候选为固定 MSIME + chinese-ime-lm rerank-v2 8M + ORT1.28.2。基础候选先显示；模型只重排合规既有候选 ID，没有可信非空上下文时保留基础排序。输入线程不等模型、不联网，不生成任意文字。
- C1 首批 34 条冻结合成语料与真实词库/native 模型对照已经完成。ORT1.28.2 带上下文首选 26/34，基础 17/34；空上下文 14/34 的退化促成保护修复。两版 ORT、v1 原错字、v2 修正、首轮失败均保留，不能将这个小集合当真实用户准确率。
- 8df2793 是空上下文保护的产品/测试修订；9aff370 只修 CI 工具链调用及失败诊断。5d976f5 是后续文档基线，其七条 workflow 全部 SUCCESS，详见 STATUS。候选 API28/35/36 的真实模型、系统 IME/Editable 与清理门禁均有成功证据。
- 首轮 API35 在种子提交后输入 shi 时组字异常结束的失败仍保留。后续成功没有证明唯一根因已解决，不得改写历史失败或放宽真实上屏/服务退出断言。
- fixed8df ARM64 开发包已独立核验签名、模型/JNI/许可/16KiB 并交付；版本仍为 0.1.0-dev。源码 Alpha 不把旧 APK 改名为新版本，不承诺跨 CI 证书覆盖升级。

## 下一轮优先级

1. 核对源码 Alpha 整合、发布流水线及实时 CI。按固定提交记录新增结果，若发布或检查失败，先复现修复后续阶段；不能把“已准备发布”说成“已发布”。
2. 用新增生命周期诊断继续定位 API35 间歇组字问题；整合首轮另有 API28 同步恢复页 actual SAF open / IOException，见[原安全记录](evidence/source-release-ci-20261107/voicekey-first/README.md)。分别复现，不能把 VoiceKey 专项成功或清理成功当恢复UI成功；保留原报告与断言。
3. 扩大公开/合成、来源明确的候选语料。先冻结文本/拼音/预期/指标，再推理；区分候选缺失、重排伤害、个人规则、补全/纠错/九键边界。现有评测不能重头标为未实现。
4. 完成无需手机的性能、失败及隐私边界工程，准备一次集中 ARM/S25 验收。测冷/热加载、长时间输入、P50/P95、RSS、能耗、隐私切换及迟到排序/点击竞态；无手机就明确未验，继续其他工作。
5. 按[开发路线](ROADMAP.md)推进设备配对/冲突/历史密钥与灾备、其他原生宿主及英语伴学。Windows PR #40 的独立 profile 和 macOS PR #12 的系统选择失败仍存在；PR #25 的 R2 缓存并发研究单独保留，不能当功能已修。

## 发布与许可边界

用户已授权把当前工作更新仓库、文档和发布。维护成熟开源项目的贡献/安全/版本文档与可追溯源码发行流程，按实际证据标 Alpha；稳定二进制仍需完成真机、签名升级及分发条件。

VoiceKey 作者同意整合的事实已记录，不反复询问同一授权。保留归属与固定来源；标准公开分发条款、第三方模型及候选训练来源声明差异分别待补充，不能由工程通过推导商业分发全清。参考[许可审计](LICENSE_AUDIT.md)和 [VoiceKey 许可边界](../integrations/voicekey/LICENSE)。本次 Release 仅根仓库源码，子模块通过固定 Gitlink 获取，不附 APK/模型权重。

## 必须保持的工程契约

候选 ID/输入代次、受限上下文、隐私字段、一个实际任务加一个最新 pending、80ms 防抖/400ms 结果预算、失败基础回退、worker 所有权与协作取消不能退化。预算不是手机性能保证。候选模型随包与语音模型单独导入是两种契约。

X-ASR 保持默认，VoiceKey 为显式可选；不静默回退云端，不存真实音频。同步仅主 App 显式 HTTPS/逐轮确认，无默认上传或后台轮询；冻结请求/ACK/cursor/整页 AEAD 事务及密钥身份保护保留。

独立切片分支和 PR，更新 STATUS/计划/证据，保留历史失败。运行对应测试：根 workspace/fmt/严格 Clippy，独立 VoiceKey 锁文件，必要时真实 PostgreSQL 事务测试；SKIP 不等于通过。编译、模型合成探针、系统 IME、ARM 真机和正式签名分别记录。临时 CI 的 root、清资料、CA 与网络规则只用于受保护的隔离模拟器，未经授权不操作用户电脑、手机、服务器或 DNS。
