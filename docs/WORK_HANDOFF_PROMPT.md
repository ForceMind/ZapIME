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
- v2原两库25/34（1.24.4）与26/34（1.28.2）须分口径；#89只复跑v2/1.24.4且逐页复现25。见[运行库归因](evidence/candidate-v2-runtime-attribution-20261108/README.md)，不代填新v2/1.28.2。
- 8df2793 是空上下文保护的产品/测试修订；9aff370 只修 CI 工具链调用及失败诊断。5d976f5 是后续文档基线，其七条 workflow 全部 SUCCESS，详见 STATUS。候选 API28/35/36 的真实模型、系统 IME/Editable 与清理门禁均有成功证据。
- 首轮 API35 在种子提交后输入 shi 时组字异常结束的失败仍保留。后续成功没有证明唯一根因已解决，不得改写历史失败或放宽真实上屏/服务退出断言。
- fixed8df ARM64 开发包已独立核验签名、模型/JNI/许可/16KiB 并交付；版本仍为 0.1.0-dev。源码 Alpha 不把旧 APK 改名为新版本，不承诺跨 CI 证书覆盖升级。

## 当前接续检查点

本次整合基线 main `af8aff12b26c2cd86c28a5b41ed837e66143840d` / tree `71972650824144dcb3c59952e541ff2ea26f6b9c` 已合并 #100 只读同步证据预览；接续仍实时查询最新 main、PR/review/CI/Release。#85/#90/#94/#97/#98 已分别完成发布创建/回读、缓存实际fill容量、SAF导航、证据容器和固定依赖受限重试，不重复实现；#25被#90取代关闭，未合并旧研究。原源码Alpha固定026bfb8与三个公开附件，不移动或覆盖。

保留 #96 与后续重复重启修复的独立分支/PR；不要从 main 丢弃 #87诊断、#89冻结扩充集、#91实验、#95工具真实触摸及已整合进展。后续[PR #102](https://github.com/ForceMind/ZapIME/pull/102)已固定远端head `52f812bc269f35ba636997b9c4664c2b83a22154` / 本机 `fe4b9bef51ade9539d814ca9860211d423e542b0` / 同tree `2c73b5abe92214ad97eb0104f11911fa6a9d3cb6`，包含本机8d3后续修复与主源树整合；本记录没有该修订最终真实系统CI通过结果。

## 最新固定原生结果：普通通过，配置目标仍失败

以下都对应 head `9b801ff947486f5418e9ddc84a88941d1cf3eda6` / checkout `436b267cbbc5885ecdf58a7515ef0323fbb6f5e8` / 相同 tree `3995757340519d1b21fc5dfb3b2b89367673d087`，attempt1无重跑：

- 完整 Android `42513949678` 最终6/6 SUCCESS，三API两轮普通输入/实际Editable/服务退出及JNI边界、三API VoiceKey模型/确认提交/UI/清理均按同源报告器复验。[完整原件](evidence/resumed-full-android-9b801ff-20261108/README.md)。
- Focused `42513949694` 最终8/8 SUCCESS，VoiceKey28/35/36、同步准备三API各57项真实SAF/身份/清理，以及API28受保护HTTPS14项通过。[focused原件](evidence/focused-native-9b801ff-20261108/README.md)。单模拟器双profile/临时CA不等于手机、公网/R2、双设备配对；HTTPS35/36仍BLOCKED_NOT_RUN。
- Candidate `42513949719` 整体FAILURE：host/build成功，API28的14项方法及系统IME通过；API36的14+4项方法及系统IME通过，新增4项仅unattached Service/Editable探针。API35在sdkmanager安装system image时`Error on ZipFile unknown archive`，AVD/原生suite未执行。[候选/配置原件](evidence/resumed-native-9b801ff-20261108/README.md)。
- 该workflow的API35配置实验已实际执行7次触摸、初始可见Insets、两次restart及设置恢复；连续编辑器未隐藏/失焦/finish，第二次start使ticket以fresh_superseded退役，未完成组字保留。原报告仍写classification=not_executed，原字节保留，但不能照搬旧callback0轮解释；本次是真实目标失败，无新空初始化finish、正文保持，停在后续i/候选提交前。普通6/6、focused8/8不能覆盖此失败，也不代填8d3后续修复。

#87/#89旧API36事件环失败、#91旧callback0实验、首轮tar/HTTP504、API28导航与发布准备失败全部保留。新9b下载和普通成功只证明该固定源码对应门禁，不改写原失败，也不统一断言全部历史间歇问题的根因。

main/#100固定4c98059/checkoutc50e375/tree7197265还有独立的新失败：8workflow最终7SUCCESS/候选42515687138失败，full42515688482六作业成功且[原报告已按同源门禁复验](evidence/pr100-full-android-42515688482/README.md)。候选API36实际14方法严格PASS，但真实系统IME第10次按键touch_pinyin_1/key_committed_unexpected_text失败（Editable7/已提交6/组字true，starts3/restart1/selection_not_owned，submitted1/scored0，framework RESET_NEW_CONFIGURATION）。源码没有新trace环，不借9b填补或认定唯一根因；清理全true仍FAIL。[原件](evidence/pr100-candidate-api36-failure-20261108/README.md)。它不同于9b API35 SDK准备失败。

PR102同tree2c73的root本机130 Android Python、pending10、根Rust143/27组0fail/0ignored与fmt/严格Clippy通过；[本机原件](evidence/pr102-integration-local-20261108/README.md)保存5份原输出及首次worktree未初始化固定submodule的Rust NOT_RUN_FAILURE，随后固定来源初始化后成功。fmt无独立日志、执行来源单列；这些不是native/Binder/手机或CI通过，不代填配置目标。

## #100 同步证据预览的实际检查与剩余任务

固定head4c98059/checkoutc50e375与本次main同tree7197265。Source42515690560通过30项发布回归、143项根Rust、fmt/严格Clippy/精确源码prepare，publish条件SKIP。Shared Windows140、macOS/Ubuntu各143项及新增9项逐名通过，Unix条件差异不当ignored或新增样本。Service42515700598实际Go1.27.3强制PG16/S3 race29测试组+1fuzz-seed/0SKIP、事务/HTTP/撤销、容器/漏洞、共享client交换/撤销/恢复、安装/重复安装/空库和对象桶灾备均通过。[固定CI原件](evidence/sync-inventory-ci-20261108/README.md)保留原pending快照和后续final。

预览仅共享Rust只读、有界metadata分页，同事务核绑定、保留墓碑；不创建operations、改冻结字节、推进cursor或发HTTP，没有JNI/UI接缝。Issue77仍open，恢复存量记录的完整首次发布/逐轮授权、先拉取/冲突与墓碑/可恢复stage仍未完成，untracked/pending0不能当已上传。上述服务证据不证明手机、生产、公网R2或配对。

C1原34条与新增64条合成集仍按冻结指标分库报告，9b host三份质量报告/native/JNI/oracle已复验；缺失5、上下文名次退步1和全部伤害保留，不称真实用户准确率。自然语音、ARM/S25、能耗和正式签名分别待验。

## 下一轮优先级

1. 实时回读 PR #102 的精确远端源码、review与所有相关CI。重复同编辑器start后续修复尚无真实系统验收，先完成配置连续性/方法/候选/全量门禁，失败先冻结，不以9b旧成功或清理PASS代填，不改语料或降低断言。
2. 候选API35的SDK准备失败与配置实验目标失败分别处理；保留完整原件、checkout/tree与排除载荷摘要。构建恢复不等于产品修复，旧None/tar/504/导航切片不重新实现。
3. 推进Issue77恢复存量记录的显式首次发布与后续配对/冲突/历史密钥/完整设备灾备；只读预览不是上传授权，保留原冻结请求/ACK/cursor/AEAD事务边界及默认无后台上传。
4. 继续候选缺失/重排伤害/九键/补全/个人规则/上下文边界的公开合成评测，新增样本先冻结预期与指标再推理；性能/失败/隐私工程不等手机。
5. 使用[S25总验收单](S25_ACCEPTANCE_CHECKLIST.md)一次集中设备验证。macOS #12与Windows #40继续保留实际系统激活/profile失败草稿，不合并为已完成；自然语音、正式签名和公开分发条款独立收口，不操作用户设备/生产/DNS。

## 发布与许可边界

用户已授权把当前工作更新仓库、文档和发布。维护成熟开源项目的贡献/安全/版本文档与可追溯源码发行流程，按实际证据标 Alpha；稳定二进制仍需完成真机、签名升级及分发条件。

VoiceKey 作者同意整合的事实已记录，不反复询问同一授权。保留归属与固定来源；标准公开分发条款、第三方模型及候选训练来源声明差异分别待补充，不能由工程通过推导商业分发全清。参考[许可审计](LICENSE_AUDIT.md)和 [VoiceKey 许可边界](../integrations/voicekey/LICENSE)。本次 Release 仅根仓库源码，子模块通过固定 Gitlink 获取，不附 APK/模型权重。

## 必须保持的工程契约

候选 ID/输入代次、受限上下文、隐私字段、一个实际任务加一个最新 pending、80ms 防抖/400ms 结果预算、失败基础回退、worker 所有权与协作取消不能退化。预算不是手机性能保证。候选模型随包与语音模型单独导入是两种契约。

X-ASR 保持默认，VoiceKey 为显式可选；不静默回退云端，不存真实音频。同步仅主 App 显式 HTTPS/逐轮确认，无默认上传或后台轮询；冻结请求/ACK/cursor/整页 AEAD 事务及密钥身份保护保留。

独立切片分支和 PR，更新 STATUS/计划/证据，保留历史失败。运行对应测试：根 workspace/fmt/严格 Clippy，独立 VoiceKey 锁文件，必要时真实 PostgreSQL 事务测试；SKIP 不等于通过。编译、模型合成探针、系统 IME、ARM 真机和正式签名分别记录。临时 CI 的 root、清资料、CA 与网络规则只用于受保护的隔离模拟器，未经授权不操作用户电脑、手机、服务器或 DNS。
