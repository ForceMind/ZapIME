# ZapIME（闪电输入法）｜源码 Alpha 之后的持续研发接管

更新：2026-11-07，源码Alpha已公开并完成三附件下载/精确源码重建复验。发布source026bfb8bf467c0d45193a50016e298c3dbb0ac01，后续main已有独立修复；不要把旧发布标签移动到新main。[发布证据](evidence/source-alpha-publication-20261107/README.md)。此文件取代旧分支交接；上一版原字节保存在[历史快照](history/WORK_HANDOFF_PROMPT_PRE_SOURCE_ALPHA_20261107.md)。

## 任务与接续入口

继续已有工程，实际修改代码、测试、修复和提交 GitHub，不从零重建，不只给计划，不需要用户反复发送“继续”。仓库 [ForceMind/ZapIME](https://github.com/ForceMind/ZapIME) 保持 Public。默认从实时 `main` 接续，源码版本入口为 `v0.1.0-alpha.1`；先读取最新 PR、CI、Release，不能把本文件当作实时 API。

整合PR #82已经合并main，已整合堆叠PR关闭；原整合分支为 `release/open-source-alpha`，功能基线是 `5d976f578a2b11d14b9050ff0ec01e5d18d9e538`，此前 PR #71/#73/#74/#75/#78/#79/#80/#81 的实际实现由该基线承接。先核对main确实包含该基线与发布提交，禁止从旧main或早期语音分支重做已经完成的工作。保留未提交内容，不强推或 reset 已有工作。

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

## 当前接续检查点

2026-11-08 UTC 本次回读的固定 main 为 `901330f400821913be0d733cc9b16a1d232f6f22`（#94已合并）；接续必须再次查询实时 API。#85 发布创建/回读修复已合并，30 项故障回归、134 项 workspace、fmt/严格 Clippy 及对应 CI 通过。#90 缓存物理 fill 容量修复已合并；实际服务运行 `42460687991` 完成 Go1.27.3 race 30 个顶层组（含 fuzz-seed 组）、0 SKIP、真实 PostgreSQL/HTTP、S3、安装和灾备。[服务原件](evidence/cache-fill-ci-20261108/README.md)与旧反例保留。#25 已关闭为被 #90 取代，未合并旧研究。

保留未合并开发链：#87 组字诊断、#89 64 条冻结质量扩展、草稿 #91 配置重启实验、#95 工具真实触摸夹具及整合草稿 [#96](https://github.com/ForceMind/ZapIME/pull/96)。#86/#94 已由#94整合合并。新的产品修复在 `fix/android-verified-configuration-restart`，已实现/本机检查和独立审查，已与配置实验/扩充集/SAF和工具入口整合；不能从 main 重做或丢弃这些进展。最终 PR/集成 head 和同源码 CI 应按实时仓库补齐，未执行门禁不代填。

#86 focused `42459710009` 的 API35/36 各 57 项门禁已严格复验；API28 实际在 navigation_close_wait 失败，roots 仍可见、文件不可见、150 轮/一次 BACK、零文件触摸。#94 只在已识别 roots 子树内导航唯一 Downloads 项，再保留真实文件触摸、字节/native 身份和清理；#94固定head7c2ab398/tree2fee68ef的focused42463933420已8/8 SUCCESS，三API各57项严格复验通过并合并；[原件](evidence/sync-recovery-navigation-20261107/accepted-42463933420/README.md)保留独立full build504失败，不称整轮全量通过。#95 仅修工具测试入口的稳定目标与实际 50ms 单次触摸，不等于产品组字修复已验。

#89 的 64 条新合成 v3 在推理前冻结，两版 ORT 主机结果为基础 38/64、上下文 50/64，候选缺失 5、上下文名次退步 1 与全部伤害保留。host/三份质量报告/native/JNI/oracle 已严格复验，同一 workflow 的 API36 系统 IME 仍实际失败；这些合成结果不是用户准确率。

## 最新原生失败与修复验证

#87/#89 API36 系统 IME 的原失败已归档：原连接未更换，事件环记录配置重启后旧 EditorInfo/选区校验拒绝→重建→空初始化 finish 原组字，模型未先请求。具体配置触发者和全部历史间歇问题仍不能统一推断。新修复保留普通 restart 强校验，仅在同编辑器/连接和精确 owned 范围下由独立有界 worker 验证新鲜状态；pending 不写正文，超时/隐藏/变更拒绝迟到，实际物理任务返回前不释放容量。还须以新精确源码验证系统 IME、远程读取及新增 Editable 探针，不能把本机编译或旧成功当验收。

#91 旧 head `6904faf` 的 API35 实验为 NOT_EXECUTED：观察者 callback 为 0，未完成配置连续性实验；设置已恢复。保留原失败，修复公开 requestApplyInsets 初始化观察者后，与安全产品实现同源码重验；真实触摸、连续连接/焦点、候选身份、完整 Editable、事件和服务退出断言不得降低。

main `5a0dc562` 的全量 Android `42462698530` attempt1已6/6 SUCCESS并按固定源码严格复验，[原件](evidence/main-android-42462698530/README.md)完整保留；旧发布源码 `026bfb8` 的 `42441322518` 最终 FAILURE 原件保留，不能称它仍是新 main 的结果，也不能用别的源码成功覆盖其 adb root/device offline/清理失败。

## 下一轮优先级

1. 实时核对 main、所有未合并 PR 的 head/review/CI 与公开源码 Alpha 身份；保留已有 tag/Release/三个附件。#85/#90 已完成，不重复实现；回读新精确源码的最终门禁并保存原始失败。
2. #94三API恢复已验，不重复。优先完成#96新鲜配置重启修复、#91实验、#95工具入口与#89扩充集的同源码系统验收。固定42f4562首轮candidate构建HTTP504，三API/新方法/实验全部未执行；SourceAlpha拒绝未检查tar.gz派生原件，保真格式修正另记新head。先修构建/来源检查再以新head执行，不能靠旧成功或SKIP代填。区分产品、测试和系统提供程序问题；失败先归档，不用盲重试或修改语料制造通过。
3. 继续来源明确的候选质量归因，保留原 34 条与新增 64 条冻结集，分别报告候选缺失、重排伤害、九键、补全、个人规则和上下文边界。后续新增样本仍先冻结预期与指标再推理。
4. 完成无需手机的性能、失败及隐私边界工程，再按 [S25 总验收单](S25_ACCEPTANCE_CHECKLIST.md)一次集中验收冷/热加载、长输入、延迟/RSS/能耗、隐私/迟到结果和双离线语音。没有手机就明确未验，继续其他工作；不在个人设备运行临时 CI 脚本。
5. 按[开发路线](ROADMAP.md)推进设备配对/冲突/历史密钥与完整灾备、其他原生宿主及英语伴学。macOS #12、Windows #40 的系统激活/独立 profile 失败仍分别保留草稿，不直接合并为完成。服务 CI 不等于手机、公网 R2、生产部署或配对验收。

## 发布与许可边界

用户已授权把当前工作更新仓库、文档和发布。维护成熟开源项目的贡献/安全/版本文档与可追溯源码发行流程，按实际证据标 Alpha；稳定二进制仍需完成真机、签名升级及分发条件。

VoiceKey 作者同意整合的事实已记录，不反复询问同一授权。保留归属与固定来源；标准公开分发条款、第三方模型及候选训练来源声明差异分别待补充，不能由工程通过推导商业分发全清。参考[许可审计](LICENSE_AUDIT.md)和 [VoiceKey 许可边界](../integrations/voicekey/LICENSE)。本次 Release 仅根仓库源码，子模块通过固定 Gitlink 获取，不附 APK/模型权重。

## 必须保持的工程契约

候选 ID/输入代次、受限上下文、隐私字段、一个实际任务加一个最新 pending、80ms 防抖/400ms 结果预算、失败基础回退、worker 所有权与协作取消不能退化。预算不是手机性能保证。候选模型随包与语音模型单独导入是两种契约。

X-ASR 保持默认，VoiceKey 为显式可选；不静默回退云端，不存真实音频。同步仅主 App 显式 HTTPS/逐轮确认，无默认上传或后台轮询；冻结请求/ACK/cursor/整页 AEAD 事务及密钥身份保护保留。

独立切片分支和 PR，更新 STATUS/计划/证据，保留历史失败。运行对应测试：根 workspace/fmt/严格 Clippy，独立 VoiceKey 锁文件，必要时真实 PostgreSQL 事务测试；SKIP 不等于通过。编译、模型合成探针、系统 IME、ARM 真机和正式签名分别记录。临时 CI 的 root、清资料、CA 与网络规则只用于受保护的隔离模拟器，未经授权不操作用户电脑、手机、服务器或 DNS。
