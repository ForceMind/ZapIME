# ZapIME 项目执行状态

## 新 API36 真实组字失败：配置重启路径已定位，安全修复进行中

#87候选workflow42459746934和#89候选42460384426均在API36系统IME失败；方法/host与API35成功不代填整轮。#87新事件环准确记录APPLY_PREEDIT→INPUT_RESTART→INPUT_REBUILT→APPLY_FINISH_EMPTY→新APPLY_PREEDIT，原host连接未更换、focused/served为true；原framework history有RESET_NEW_CONFIGURATION。保留条件以selection_not_owned拒绝后，空初始化回复结束原组字，把原拼音留在正文，下一键开启新组字。具体哪个设置/配置触发者及失败时旧EditorInfo坐标仍未证实。

#89出错前模型submitted/scored均0，真实种子已提交；GitHub compare核对其29文件未修改相关产品/模型实现。两份原始ZIP/固定源树/失败时服务与宿主记录/SHA已保留：[新事件环原件](evidence/api36-configuration-failure-20261108/README.md)及[候选扩展原失败](evidence/candidate89-api36-failure-20261108/strict-failure-summary.json)。清理/真实服务退出成功不改输入FAILURE。

新的修复从#87另开分支fix/android-verified-configuration-restart：保留普通restart强保护，只在相同编辑器/连接及原owned折叠组字条件下用独立有界worker做新鲜有限状态验证，pending不写正文；超时/失效拒绝迟到。该产品修复仍在编码/测试，尚未提交或验收，不能把诊断或#91草稿实验称已修复。

## 当前研发检查点：发布修复已合入，原生与服务切片继续验收

实时main为331def64ea0e3d5492712ff2066ec559f036015f。#85已合并发布脚本修复，实际复现旧None TypeError；源码准备CI42459653225通过30项故障回归、134项workspace、fmt/严格Clippy和精确归档，相关共享/Apple、Windows、服务及CPU检查也通过。合并范围只涉及发布脚本/回归/文档；其Android三条workflow当时仍运行，本次main新CI另按实际结果回读，不借旧成功代填。

| 独立切片 | 当前交付与门禁 |
| --- | --- |
| #84 / #88 | 已合并原main最终FAILURE原件与一次集中的[S25验收单](S25_ACCEPTANCE_CHECKLIST.md) |
| [#86](https://github.com/ForceMind/ZapIME/pull/86) | SAF受限诊断已提交；focused42459710009的API35/36恢复11+10+14+12+10项已下载严格复验，API28仍排队；旧API28根因未决 |
| [#87](https://github.com/ForceMind/ZapIME/pull/87) | 有界组字事件环、上层epoch拒绝与真实Editable数字诊断已提交；本机编译/回归通过，原生CI继续运行 |
| [#89](https://github.com/ForceMind/ZapIME/pull/89) | 新64条v3先冻结、两版ORT真实主机结果已保留；35项相关测试过，实际host作业128668210879已成功；三份新CI质量报告、15组native/36项JNI/独立oracle均已下载严格复验 |
| [#90](https://github.com/ForceMind/ZapIME/pull/90) | 缓存第33个后台读缺陷已复现，32实际fill修复及race×10/vet/build通过；真实PG/HTTP/S3/灾备等待CI |
| [#91](https://github.com/ForceMind/ZapIME/pull/91) | 堆叠#87的独立API35框架重启实验，保持草稿；5项严格报告测试与本机编译通过，未声称实际复现/修复 |

公开v0.1.0-alpha.1仍固定026bfb8源码与原三个附件，不移动标签、覆盖资产或新增二进制发行。#12/#25/#40仍未合并；#25的研究不等于#90的真实事务验收。旧原main的42441322518失败和历史组字/SAF失败全部保留。新原始CI证据见[本轮原件](evidence/current-slices-20261107/README.md)。以下均为固定时间/源码的历史记录，较早pending不覆盖本节新事实。

## 发布草稿回读健壮性修复（2026-11-07，独立切片）

已在87a0a0a中复现旧None TypeError并改为保留REST创建响应ID、按ID有界只读确认；丢响应不重复POST，畸形身份/权限/冲突明确拒绝，部分上传只核验并补缺失。原17项与新增13项真实Git/故障状态机回归全部通过，Python编译与差异检查通过，[证据](evidence/release-draft-recovery-20261107/README.md)保留旧脚本复现与新源码摘要。本切片CI尚待，未触发新发布；原源码Alpha标签/附件不动。

## 主分支 Android 全量最终结果（2026-11-07 客户端日期）

已实时核对 main 仍为58a3a546c758378d1887aeeaa99b4bd4d3a25c77、Public、源码Alpha标签和公开附件保持原身份；#82/#83已合并，#12/#25/#40仍为未合并草稿。发布源码026bfb8的全量Android42441322518最终 **FAILURE**：常规API28/35/36和VoiceKey API28/35成功，VoiceKey API36在准备阶段adb root/device offline失败，未进入模型instrumentation，随后本轮共享包清理也失败。不能用前五项成功代填整体。原artifact已下载核SHA，三份报告/完整job日志和元数据见[最终回读](evidence/main-android-final-20261107/README.md)。

该失败独立于原API28恢复SAF打开IOException和历史API35间歇组字；没有确认共同根因。下方“仍运行”是旧快照；后续切片继续按各自精确源码和CI验收。

## v0.1.0-alpha.1 已公开发布（2026-11-07 客户端日期）

[源码Alpha](https://github.com/ForceMind/ZapIME/releases/tag/v0.1.0-alpha.1)已实际公开，Release438003228，发布时间2026-11-08T03:17:38Z；固定source026bfb8bf467c0d45193a50016e298c3dbb0ac01/tree9aa8d7e0850ba110357dc65886f882f4370529ab。PR #82已合并开发栈，已整合的重复PR均关闭，未完成的#12/#25/#40继续保留。

已从公开URL实际下载源码包/metadata/SHA256SUMS并核验GitHub摘要、文件校验与精确提交的逐字节重建。源码包8,085,024字节，SHA d78a5f0b5845e1aa8b67dd7671cee4c0cde1a0f40b018e70e0044f990c7f5cc9；不含子模块内容、APK或模型权重。完整证据见[发布与下载复验](evidence/source-alpha-publication-20261107/README.md)。

发布首轮创建正确空草稿后即时查找返回None而失败；仅重跑同源发布job，第二轮实际公开及复验成功。原失败日志保留，不能称代码已修复该可见性问题；后续补稳健创建/回读与明确错误处理。主分支source/共享及Apple/Windows/sync/CPU均SUCCESS，全量Android在本快照仍运行，后续以实时GitHub为准。真机、正式签名、许可及原系统输入/SAF失败仍按各自证据待验。

## 源码 Alpha 整合与最终 CI 回读（2026-11-07）

功能/文档基线5d976f578a2b11d14b9050ff0ec01e5d18d9e538的七条workflow均为SUCCESS：候选42407415284、完整Android42407415282、VoiceKey42407415283、共享/Apple42407415237、Windows42407415301、同步42407415255、CPU42407415336。9aff370候选专项42407002808亦全部成功。早期pending/failed记录保留为当时快照，不再代表最终结果；原API35间歇组字失败未确证根因。

最新候选运行的原始ZIP、固定build checkout与严格复验结果另存于[源码Alpha证据](evidence/source-alpha-20261107/README.md)。

本轮本地134项workspace测试、fmt、严格Clippy、4项配对统计、7项质量runner以及17项源码发布真实Git/状态机测试通过；YAML与当前文档本地链接检查通过。发布整理没有修改产品输入、模型或同步实现。

发布流程实际第二轮发现Rust缓存恢复旧源码包，create-only准备正确拒绝，已将产物移至各job独立run/attempt临时目录；原失败及修复见[发布CI证据](evidence/source-release-ci-20261107/README.md)。草稿查询已改为已发布tag查找加认证分页/ID回读，17项测试覆盖中断重试。

整合首轮67da3cc5的VoiceKey API28/35/36专项成功，但同workflow42440405925的独立API28同步恢复UI在actual SAF open / IOException失败；前序导出/native身份/确认通过，清理通过不代表恢复成功。原安全日志与范围见[同步恢复失败](evidence/source-release-ci-20261107/voicekey-first/README.md)。该结果保留为独立未决问题；修正后的发布workflow已经通过官方actionlint1.7.11，等待实际发布门禁。

本轮以release/open-source-alpha承接整个已验开发栈，新增中英文首页、社区规范、问题/PR模板、文档索引、路线图、变更记录、可追溯源码发布流程和新接管入口；更新过时SECURITY联网/同步描述。发布状态以GitHub Release及对应workflow为准。源码Alpha只含根Git树，不附子模块内容、APK或模型权重；不将开发证书包装为稳定升级签名。

VoiceKey公开分发条款及候选训练来源声明差异仍未决。ARM/S25、自然语音、长期质量/能耗、Apple/Windows系统输入与四端同步/配对/完整灾备尚未完成。当前工作与后续任务见[接管入口](WORK_HANDOFF_PROMPT.md)、[路线图](ROADMAP.md)与[发布说明](releases/v0.1.0-alpha.1.md)。

## 固定ARM64候选与API35复验（2026-11-07）

已下载并独立重验8df固定产品的API35第二轮14项方法/schema2真实系统IME/清理证据，与原API28/36成功分别保留。三API现各有同源码正向结果，API35首轮组字异常的唯一根因仍未解决，不能称稳定。

两ABI产品包已下载校验同开发签名/v3/16KiB/固定模型/词库/JNI/许可；ARM64候选58,745,799字节，SHA a79adf68d87eda210fc3ec95569b0760255220dad35eb9070a5eeae2fbde1cf3。[安装与固定包](CANDIDATE_RANKING_TRYOUT.md)、[复验及签名原始记录](evidence/context-guard-20261107/README.md)已更新。后续9aff只改测试诊断和CI，最终workflow仍运行中；原host失败不改写。


## 空上下文回退首轮实际CI与复验（2026-11-07）

PR #80的固定7ff77a9六条workflow已全部SUCCESS；PR #81固定8df2793的完整Android、VoiceKey、共享核心、Windows、同步、离线语音六条成功。新候选专项API28/36的14项方法门禁/schema2真实系统IME均通过并实际下载独立重验，原ZIP/固定身份/清理证据见[本轮归档](evidence/context-guard-20261107/README.md)。

候选workflow仍FAIL：host在自动安装Clippy组件冲突后未运行质量评测；API35方法门禁adb root失败，另system gate在种子提交后的shi输入阶段意外结束组字。空上下文无请求/种子你好已通过，不能把后者归因于前者或忽略。已启动同源码复验，并修复host显式toolchain选择、补充失败时固定生命周期/长度诊断；不改产品输入或降低原断言，新CI待运行。


## Android空上下文保留基础候选（2026-11-07，独立修复切片）

依据PR #80的跨运行库合成基线，新增非空可信上下文的生产调度条件。默认增强仍开启；空链不提交新的LM请求，正常接受键盘提交或允许初始快照后恢复。沿用原隐私、焦点、代次、取消与模型契约，未改共享核心/词库/权重/语音。

已扩充纯JVM上下文/worker/资源检查和schema2系统IME驱动：先真实按键验证空上下文无请求，再真实候选提交种子，验证模型排序及完整prefix+所见候选进入Editable；第二次冷暖尝试必须重新形成上下文。3组JVM和14项相关Python检查通过，产品及独立测试宿主已按官方API36r02/Java8源码目标编译。新的API28/35/36系统门禁尚待本切片CI，不能借PR #80/旧schema1成功填充；父API35失败仍保留。


## 候选质量与资源基线（2026-11-07，PR #80）

已将配对统计接入真实生产词库、release core和native8M模型，完成34条冻结合成用例、空/有上下文及两版运行库对照。v2/ORT1.28.2基础首选17/34，带上下文26/34（挽回9、损害0）；空上下文14/34（损害3）。1.24.4带上下文25/34，表明运行库差异不能忽略。小型人为语料不代表用户准确率，主机IPC/P95/RSS也不代表手机体验；未据此改变产品默认或调度。

语料v1两处拼写错误已在v2独立冻结修正，原语料/报告/首次core open失败均保留。[原始证据、资源数据及范围](evidence/candidate-quality-20261107/README.md)、[工具与复现](CANDIDATE_EVALUATION.md)已更新。134项workspace、fmt/严格Clippy、真实native15组/JNI36项/独立oracle、新7项runner及原4项聚合测试通过。初版v1的CI host作业成功；最终v2源码CI待回读。

父PR #79的候选等6条workflow成功，完整Android API35仍FAIL（原Launcher ANR遮挡启动、独立SAF失败）；安全原始现场已下载保存，不将旧失败改写为通过。下一切片应验证空上下文保留基础排序及重跑原系统门禁，继续更广语料与S25/其他宿主任务。


## 候选配对统计入口（2026-11-07，接续 7c1a0779）

新增离线合成观测统计器及 CI 单测，统一首选/前三/MRR、缺失目标、排序改善与退步、P50/P95。报告仅含聚合和输入摘要，拒绝非配对候选集、重复 ID、非法计时及超量输入，create-only 保存结果。实现与调用边界见 [评测说明](CANDIDATE_EVALUATION.md)。

这是统计基础设施，未连接 Android 采样器、未执行新模型推理或手机评测。主机四项统计/CLI 回归通过（含多目标、缺失分母、11类无效数据、隐私错误输出、输入上限及覆盖保护）；新 CI 尚待运行。生产输入/模型/默认开关未修改。S25、质量语料、能耗与其他平台门禁继续待验。


## 工作对话交接同步（2026-11-07）

[当前交接提示](WORK_HANDOFF_PROMPT.md)和[开发计划](DEVELOPMENT_PLAN.md)已统一为 `feat/offline-candidate-ranking` / PR #78，并明确其手动同步基线、已验源码身份及候选质量/资源的后续任务。旧交接按原字节保存在[历史文件](history/WORK_HANDOFF_PROMPT_PRE_CANDIDATE_20261107.md)，不再将早期语音分支或已完成的 `startVoice()` 修复作为当前入口。本次仅更新文档；以下七条成功 workflow 仍属于固定测试修订 `5e1a87c`，不声称在文档提交上重复执行。

## 默认开启的本机候选增强（2026-11-07）

**Android 开发版已接入并默认开启本机候选增强，三端专项实测全部通过，固定双 ABI 安装包已下载复核。** [试用包与开启说明](CANDIDATE_RANKING_TRYOUT.md)、[实现契约](CANDIDATE_RANKING.md)、[完整原始验收](evidence/candidate-ranking-20261107/accepted-42361234304/README.md)均已保存。

[PR #78](https://github.com/ForceMind/ZapIME/pull/78) 的产品实现固定 `3a11177b7d0b2ef054183f5297a282dcb650a14f`，最终测试修订 `5e1a87ccc345cbdb41635059c35cb63ce988b197`。从手动同步基线 `f727d203` 接续，采用固定 MSIME + chinese-ime-lm rerank-v2 8M + APK 已有 ORT 1.28.2。实际 CI merge checkout `4985350e8a7e30257afb58f12cf938c056ab8618` 与测试修订树相同。分支保持开发审查状态，尚未合并 main 或发布稳定版。

Android 新偏好默认开启，用户明确关闭的值保留。基础候选先显示，独立有界 worker 只重排最终可见的精确读音候选；个人规则、补全、部分上屏与原候选 ID 保留，输入线程不等待模型。当前会话最多 64 个码点上下文，敏感/禁止学习/无建议/搜索字段排除，资源或推理失败沿用基础候选。模型随包固定校验，不上传输入。其他三个原生宿主尚未加载该模型。

[候选专项 42361234304](https://github.com/ForceMind/ZapIME/actions/runs/42361234304) 五作业全部 SUCCESS。API28/35/36 各 **14/14** 实际模型/JNI/MSIME/Editable 检查通过，生产分数与固定上游张量的同运行库独立参考误差均 **0**，门槛仍 0.001。另三条真实系统 IME 路径均通过：默认偏好、生产词库/模型、6 次 `shijie` 触屏输入、当前 revision 的排序发布、点击所见“世界”精确进入 Editable、无 composing span；原系统键盘/设置、服务退出、合成资料与 IPv4/IPv6 规则清理全部验证。不是用方法探针替代系统键盘，截图也不作为绘制帧或真人上屏证明。

主机完整 workspace **134 项**（其中候选 **16 项**）、fmt、严格 Clippy 通过；Android Python **95 项**、资源准备 **14 项**、三组 JVM worker/上下文/资源检查通过。最终 CI 实际执行 native **15 组**、生产 JNI/JVM **36 项**及独立 oracle；普通页误差 0，本轮分批差 0.017479419708251953，仅描述该主机环境。真实生产库 3 组合成排序提交及过度补全/可见页修复前后原报告保留，不将机械通过冒充输入准确率。

既有回归分别保留身份：相同产品载荷的 a73 完整 Android [42360463238](https://github.com/ForceMind/ZapIME/actions/runs/42360463238) 六作业全过，VoiceKey focused [42360463313](https://github.com/ForceMind/ZapIME/actions/runs/42360463313) 八作业全过。最终测试修订 5e 的[完整 Android 42361234345](https://github.com/ForceMind/ZapIME/actions/runs/42361234345)六作业和[VoiceKey focused 42361234391](https://github.com/ForceMind/ZapIME/actions/runs/42361234391)八作业均已完成成功；三端实际系统 IME/服务退出/共享文件/网络清理原件已核。该固定修订的七条 workflow 已全部 SUCCESS，包括候选、共享核心/Apple、完整 Android、VoiceKey、Windows、离线语音 CPU 和独立同步服务。最终元数据另存，较早的进行中快照保持原样；后续文档提交不改写已验源码身份。

两份产品 APK 已实际下载，复验 SHA256、v3 签名、16 KiB 对齐、固定模型、原生库和许可证。后两次修订只有测试与证据变化；APK 内容逐成员相对首轮实现仅开发签名证书不同。首轮跨 ORT 参考失败、第二轮 adb shell 分号设置恢复失败均保留原字节，修正未放宽数值/读回/清理门槛；完整过程见验收目录。

S25/ARM 手机、长时间输入、真实语料首选率/误排序率、RSS/P95/能耗、25M 对比、其他三端模型宿主、正式签名/稳定发行及训练来源声明差异的最终澄清仍分别验收。本次没有操作用户设备或服务器。

## 前序手动同步进度（保留原记录）

更新时间：2026-11-07 00:13 PST（America/Los_Angeles）。本轮已实际开发、测试、保存GitHub并下载核验APK。累计汇报次数无可靠连续记录。

## b86实测结果与新页驱动复验（2026-11-07 00:13 PST）

固定 `b86e4f27fcce2c5d0bd394b139463bb52ea5fbf9` 的新API28 HTTPS作业 [128347475674](https://github.com/ForceMind/ZapIME/actions/runs/42353573084/job/128347475674) **SUCCESS且已实际下载重验**。三阶段2+10+2项全部通过：未信CA拒绝、临时系统CA后生产Android同步访问真实Go/PG、wrong SAN拒绝、删除CA后的新进程再次拒绝。双profile上传2条、cursor均2/head2，badSAN HTTP0；137项原系统CA基线、临时证书/staging、产品UID10067双族精确规则、测试包、Go/TLS/私密目录清理全部通过。[原ZIP/原joblog/独立验证](evidence/manual-sync-20261106/b86-https-api28/)保留精确head/checkout/tree及scope，不能称原厂根库、两台手机、35/36 HTTPS、公网/R2/配对已验。

[共享42353573085](https://github.com/ForceMind/ZapIME/actions/runs/42353573085)四job全部SUCCESS：mac118、Ubuntu118、Windows115（3项Unix专用不参与Windows编译，非SKIP失败），AppleHost3个模拟器XCTest及macOS/iOS开发宿主编译通过。原mac夹具失败修复已在真正mac重验，未放宽NOFOLLOW。[完整原日志](evidence/manual-sync-20261106/b86-shared-ci/)保持原字节/CRLF；Apple ZIP只核元数据未下载，不虚构真机/公证。独立[服务42353573054](https://github.com/ForceMind/ZapIME/actions/runs/42353573054)的新client17命令、23个Go测试/0SKIP、PG/S3、安装及新DB/对象桶恢复也全部通过并下载核验。

b86 focused整体**仍失败**：三端原47项均通过，但新增页面10项均在 `key_queued_cancel_before_io` 第4项失败，前三项通过且清理完成。[三端原失败](evidence/manual-sync-20261106/b86-pages-first-failure/)不改写，原报告只有wrapped Exception，没有伪造inner cause。源码显示测试在同一主线程点击正按钮后立即读request，尚未给AlertDialog异步派发listener的机会；这是修复依据与待复验推断。当前仅把正常点击与新鲜排队观察拆开，并等待主线程实际派发；真实queue==1、latch/drain/barrier、cancel/selection/generation/迟到、DB/vault及清理断言全部保留。失败阶段只增加固定白名单诊断，不使FAIL通过；drain第二错误保留为原异常suppressed。主机全Java编译与6项严格validator通过，新三端页面**待本次提交CI**。准确范围为真实finish后失活/迟到拒绝，未单独计数onStop回调或证明活动HTTPS强制中断。

本次只修test-only驱动并归档证据，不改生产APK逻辑、平台信任或权限。累计汇报次数无可靠连续记录；当前手机/原厂PKI/实际R2、正式签名与四端完整产品仍未验收。

## 当前试用包与HTTPS验收接续（2026-11-06 23:58 PST）

最新已实际取得的Android候选仍固定 `e3c4da10b5f90917c2c8d17aeb8fc382f78ec4f9`，已提供[双ABI试用与固定版本一键部署](ANDROID_MANUAL_SYNC_TRYOUT.md)。原ZIP235395045字节/摘要、两APK、相同开发签名v3、18 core JNI、许可证、模型排除、16KiB与默认TLS/private页面均已实际检查；[包校验原件](evidence/manual-sync-20261106/apk-e3c4/README.md)。没有安装或操作用户手机/服务器。

固定e3的focused42351436372七作业及full Android42351436401六作业全SUCCESS，三端各47项严格重验和原键盘/语音/资料/备份/隐私/系统IME退出结果分别保留；新共享客户端服务42351436402全SUCCESS，实际17命令真服务/双库、PG/S3与安装灾备完整通过。完整证据已保存[本轮目录](evidence/manual-sync-20261106/)。

共享checks42351436378的macOS测试**实际失败**：SQLite NOFOLLOW拒绝 `/var` 祖先系统别名下的临时路径。原log与Linux精确反例保留；只把有效测试/自有示例父目录规范化，保留产品writer函数体、Android JNI、Cargo.lock和全部符号链接/schema/预算保护；新增父别名拒绝、规范路径通过回归。修复后主机workspace118/fmt/strictClippy及JNI host3通过；修复后的macOS必须等新CI，不用Ubuntu结果代填。

继续新增test-only验证：10项真实新页面确认/负取消/实际worker排队取消/恢复本机读取与清理，以及独立API28真实HTTPS作业。原产品平台TLS/hostname不改，仅在严格受保护的一次性API28系统副本加入本轮CA；必须before不信任、真实Go/PG双profile传输、wrong SAN拒绝、after再次不信任及根库/自有网络规则恢复。细节见[ADR-023](ADR_023_ANDROID_SYNC_HTTPS_CI.md)。主机Java编译、80项Android Python（含29个HTTPS guard/report检查）、真实host TLS/Go/PG已通过，Android新增页面10项及系统CA/HTTPS三阶段仍NOT RUN，待新源码CI。

e3旧候选没有手机HTTPS/公网PKI/实际R2证明；未来受控CA测试也不等于原厂信任库、两台手机、S25、配对或四端同步完成。仍不合并/发布稳定版，不触碰用户设备、DNS或生产服务。距23:28收口约30分钟；累计汇报次数无可靠连续记录。

## 新工作切片：显式手动加密同步（2026-11-06 23:20 PST）

[PR #73](https://github.com/ForceMind/ZapIME/pull/73) 固定 `931d30ec478288e910f9c0d884fa5e9024c88f61` focused [42349924323](https://github.com/ForceMind/ZapIME/actions/runs/42349924323) 的本机准备三端全部通过；原ZIP已实际下载，严格复验每端11项JNI/Keystore及10项真实系统文档UI、同根/缺材料恢复、单次触摸和完整清理。API35本轮确实观察抽屉覆盖并关闭一次；API28/36未观察到覆盖，未声称运行关闭分支。[固定源码三端原始证据](evidence/sync-preparation-20261106/accepted-saf-42349924323/README.md)保留旧失败及新成功的独立身份，不用于代填下面新传输代码的结果。

[PR #74](https://github.com/ForceMind/ZapIME/pull/74) 保存服务端密钥身份CAS：实现提交 `1858895a490dad18de9696ff772ef6db410ef9f8`，合入931后为 `e8e016cb6fe932d58cad2b04ed5ed6ab0f89ed9e`。GET/PUT `/v1/key-descriptor`、当前owner首次登记、同根幂等、不同根/legacy停止、Push异key拦截和schema3防降级已有实际Go/PostgreSQL/race/vet、旧二进制拒绝及真实根材料/pg_dump新库恢复证据；[原始日志与限制](evidence/sync-key-descriptor-20261106/README.md)。对应Docker/S3/安装器CI仍按实际结果更新，不当作本地已经运行。

最新开发分支 `feat/android-manual-sync` 基于上述服务merge，接入 [手动同步实现](ANDROID_MANUAL_SYNC.md)：共享typed Descriptor、实际持久冻结字节及AEAD快照核对、严格ACK与认证整页入口，4项独立JNI；Android独立密钥核对/登记和单轮传输页面。确认锁定所见server/account/device/key指纹，网络前后复核完整连接与实际材料的DB根指纹；默认不上传，无后台轮询或自动重试。冲突、旧历史/缺key与完整新设备灾备未解决事项仍明确保留。

主机原session TLS31、descriptor TLS26、transfer TLS43及有界队列检查通过；轮次策略41条断言通过，涵盖先身份/拉取后stage、原buffer传递、16次上限、未知ACK不自动重试与取消后保留已提交计数。源8/API36 Java编译通过。共享Rust、额外JNI/密钥解析/真实服务端到端与新源码Android三端检查继续完成，待固定提交和对应CI记录；不沿用931准备层或08语音包替代。手机HTTPS/多设备、S25/自然人声及正式签名仍NOT RUN。

23:28 PST收口更新（距23:20检查点8分钟）：新共享客户端/存储/原生桥工作区114项测试、严格fmt/Clippy、实际JNI源的Linux主机3项边界及strictClippy、Android Python47、source8/API36全部产品与voice-tests Java编译通过。真实回环TLS31+26+43、有界worker、轮次策略41条及文件IO9项通过。使用生产existing-v2写连接的新共享client已在真实Go/PostgreSQL上跑17条CLI命令，双库加密交换、原密文丢ACK重放、重复确认、无回声、错根前拒绝与坏后半页回滚/原密文保留均通过；[原始主机/本地服务/服务CI证据](evidence/manual-sync-20261106/)固定源SHA。新Android14项密钥和12项transfer专项已写入独立CI硬门禁，实际执行仍待对应提交。

PR74的固定e8e016服务[CI42350448294](https://github.com/ForceMind/ZapIME/actions/runs/42350448294)已SUCCESS：真实PG+S3的23个顶层Test（0 SKIP）、容器、vuln、原双设备灾备、一键/重复安装及全新DB/对象桶恢复均通过，原artifact ZIP已实际下载重验。不能把这份旧fixture服务CI当成新manual client Docker/S3通过。新落库词库/候选在新输入会话生效，保留组字的旧handle不即时热更；新UI和指南已说明。

累计汇报次数无可靠连续记录。所有切片尚未合并、未发布稳定版、未操作用户设备/生产服务器/DNS，仓库保持PUBLIC。

## 新工作切片：本机同步材料准备（2026-11-06 22:23 PST）

新分支 `feat/android-sync-preparation` 从本页固定Android候选源码接续，单独审查同步准备，不改变候选包的源码/摘要身份。实际新增typed密钥与专用恢复包、受保护create-only存储、SQLite账号/key ID/根指纹原子绑定、独立JNI、准备页与严格11项Android探针。用户显式操作，零HTTP/零自动上传。

主机工作区99测试/fmt/严格Clippy、Android Python38、文件IO9、全部产品和voice-tests API36 Java编译、133包核心许可收集通过。对应Android JNI/Keystore/页面与双ABI构建尚待新CI；正向SAF与手机未运行，不能使用旧包代填。详见[本机准备实现及限制](ANDROID_SYNC_PREPARATION.md)。原密文存在但包装密钥丢失时仍拒绝原地重置；没有完成远端密钥登记/配对、实际HTTP同步或完整恢复。

第一实现提交 `9e095fccd78dbb2240e147d60d9543143f8c7d43` 已保存于 [PR #73](https://github.com/ForceMind/ZapIME/pull/73)，base为PR #71开发分支。后续增加严格10项新页SAF UI作业、缺材料禁创建UX和同步worker8项队列上限；新主机Python40、实际TLS31/队列恢复、全部Java编译通过。Android构建/11项方法与10项提供程序UI仍等待真实结果，不把测试代码存在写作通过。

23:05 PST 实证更新（距本页22:23检查点42分钟）：`9e095fcc` focused [42347009455](https://github.com/ForceMind/ZapIME/actions/runs/42347009455) 七作业成功，API28/35/36各11项真实JNI/Keystore/Activity检查通过；ZIP、完整job日志及摘要已实际下载固化于[首轮证据](evidence/sync-preparation-20261106/first-native-42347009455/README.md)。

`5b2d9488` focused [42347662267](https://github.com/ForceMind/ZapIME/actions/runs/42347662267) 双ABI构建与三端VoiceKey专项成功，准备层三端原生11项通过；新页SAF UI仅API28的10项通过，API35/36均在实际打开文件失败。原报告、完整日志、失败截图及严格冻结校验器身份已保存于[SAF首轮证据](evidence/sync-preparation-20261106/saf-ui-42347662267/README.md)。现代系统截图显示导航抽屉覆盖测试文件名，而底层无障碍节点仍报visible。当前只修测试驱动：观察到特定DocumentsUI导航区域覆盖时执行一次公开返回动作，确认区域消失、重取稳定文件几何，再执行原来唯一FINGER触摸；增加相应报告硬门禁。不是通用遮挡检测或产品恢复逻辑修复，新提交三端CI待运行，不能把清理成功或原生通过写成SAF成功。

后面的双离线候选结论对应其原固定源码与产物。累计汇报次数无可靠连续记录。

## 当前交付结论

**Android 双离线引擎 Alpha 候选现已可供试用，也可以交本地 Codex 接续。** 产品源码固定 `08e84980562ecc8fab436ffcdab6e5aee8c567e7`；后续测试提交 `be79d084eef1f835e7504f134f46ba315b36df18` 未改变产品路径，在 focused [run42227414903](https://github.com/ForceMind/ZapIME/actions/runs/42227414903) 完成 API28/35/36 全部专项。固定 APK、SHA、签名、独立模型准备、离线使用和备份说明见 [Alpha 试用指南](DUAL_VOICE_ALPHA_TRYOUT.md)。

这是候选试用层级。三星 S25/ARM Android、自然人声、实际 OS 撤权、同机质量与能耗、正式签名仍 NOT RUN。四端完整产品、E2EE配对/资料同步/完整恢复、用户服务器实际部署及英语伴学实际翻译/UI未完成。X-ASR 继续默认，不预先推荐 VoiceKey。

PUBLIC 仓库 ForceMind/ZapIME；分支 `feat/voicekey-engine-import`，复用 PR #71 / Issue #72。main最新核验 `5621ab0e81780ca58c6cf74e97c93928a10f981a`。未强推、未合并、未改可见性、未发布稳定版，未操作用户电脑、手机、服务器或 DNS。当前文档和证据提交会晚于上面的固定产品及测试提交；不能把新触发且尚未结束的 CI 当作已通过。

## 实际实现

- 入口以 `VoiceEngineSession` 固定引擎，独立模型准备和 worker 校验；覆盖仅 X-ASR、仅 VoiceKey、两者都有、均无、所选模型损坏。使用 VoiceKey 不要求安装 X-ASR。
- Rust 单物理解码槽、真实模型内协作取消、有界 PCM/partial、无样本/全零空结果、没有新 partial 保留预览、尾块与真实 worker 回收。不是 abort 等待即强杀推理。
- 真实 JNI 背压定位后改为专用语音 worker 最多5秒取消感知等待；不增队列、不丢帧、不重复计数。停止/排空/final 共30秒，重复停止不延期；main Handler 独立 watchdog 协作取消并拒绝迟到结果。
- 模型缓存原子替换身份、导入缓存隔离、闲置释放；JNI参数/异常和新 `finish(long,int)` ABI 边界。确认前多处复查权限，原输入连接、ticket/epoch、选区、上下文与隐私事务最多插入一次。
- 官方固定原包及全部成员尺寸/SHA受信清单、有界导入、暂存验证/恢复与旧有效模型保留；17类存储故障夹具。真实 ENOSPC、断电/杀进程与手机提供程序仍需独立验收。
- 锁依赖与固定 vendor，独立32包依赖许可、8JNI、动态依赖、16KiB/签名/模型排除硬门禁；保留来源、不编译上游非公开云协议或凭据。

关键提交：`20f68dcc`来源审计、`5bbfe310`运行时/入口/可信导入、`c7b98ebb`权限确认复查、`767ead79`精确失败证据、`45d9057b`独立模型无权重测试链、`08e84980`背压/预算/独立producer、`c56b3cbc`真实手指测试事件、`be79d084`隔离模拟器软键盘准备/读回/恢复。

固定上游：VoiceKey `3ba017826eef7fac405198510b03859eb5b6c8c4`；MSIME `97d652a49cabb0887355b84186af2aa9032d3814`。vendor未修改。

## 当前真实证据

| 范围 | 实际结果与覆盖边界 |
| --- | --- |
| 仅 VoiceKey 的 Android 专项 | be79 focused42227414903，API28/35/36全部SUCCESS。实际下载ZIP、核GitHub digest，再用严格校验器重验；每端14主案例、23条真实Editable、5条JNI边界、30秒受控watchdog、模型版本/SHA、仅所选模型、预览/确认和无迟到上屏检查通过 |
| 音频生产与队列 | 每端独立单调时钟 producer 使用生产 VoicePcmQueue；125490原样本+371零尾块=125861全部生产并接受。没有以同步 accept 放慢 producer；不等于自然麦克风 |
| 系统 IME | 三端实际激活、静音 AudioRecord、停止/取消/重录/确认保护、切回已核系统IME并等待服务退出；IPv4/IPv6精确产品UID离线规则及移除、原测试文档清理均通过，无产品 fatal/ANR。静音不能证明自然语音质量 |
| 测试环境恢复 | API36实际读到原软键盘设置0，设1/读回1并恢复0；API28/35原1并恢复1。仅临时专用模拟器，未改产品显示策略、反射强建视图或降低断言 |
| 原有输入与 X-ASR | 固定产品08 full42221304627的API28/35/36原输入/X-ASR/主题/资料/隐私/备份/真实退出均PASS。该旧运行的VoiceKey现代驱动失败仍保留，由be79专项独立复验通过，不能把旧整个workflow称全绿 |
| Native ARM/x86 | 产品08 run42221304426，两架构各26测试、数值极值/尾维度参考对照、严格fmt/Clippy/locked及微内核诊断PASS；是Linux原生ARM，不是Android手机 |
| 真实固定模型CPU | 产品08 run42221304326，两架构实际生产Java导入、断网Rust推理、空/零/±1及取消回收通过；下载metadata尺寸/SHA核对，无权重/音频回传。CPU辅助喂入不同于JNI push_wait，结论分别记录 |
| 主机检查 | 根Rust87、独立适配器26、JVM路由/预览/预算14及模型存储17；Android Python35，产品/API36 Java编译通过。模拟权限ContextWrapper不等于OS撤权 |
| 固定产物 | full08 artifact13503554301实际ZIP234974569B/SHA256 `5e18aebd7a15b575acc8c01d0ea8bb77c44bce222b9e35d6084d6da23b2aec73`；ARM与x86产品、签名、源码/32许可/8JNI/5库16KiB/官方SDK dexdump均核验，详细APK SHA见试用页 |

专项三模拟器的首非空partial/producer EOF到final分别：API28 1400/1625ms、API35 1400/2173ms、API36 1900/1588ms。这是固定单句合成fixture的一次运行，不是 P50/P95、自然人声或 S25 端到端速度；30秒watchdog探针是实际模型已加载的空闲worker受控取消，不声称覆盖活动矩阵计算强制终止。

## 质量、失败与授权边界

同一125490样本PCM、4线程，原生ARM/x86六轮输出一致；固定speech-v1 CER=15/22≈68.18%，全部错误保留。重复不算新增语料，不用100%-CER称准确率，不据此推荐第二引擎。自然人声与两引擎公平对比尚未运行。

首次、第二轮、focused背压失败、08现代provider失败及c56的API36软键盘拒显原件完整保存在本轮证据下。没有删失败或放宽输入/时限/模型断言；最终的手指事件及CI软键盘准备均为测试环境修补，产品仍08。

官方模型身份审计run42209579465：`wechat-offline-20260710220526250`，原包102920639B/SHA256 `b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863`。已确认作者允许源码整合，保留署名；不由此推导第三方权重再分发权。VoiceKey权重不进入APK/公开测试产物/备份/R2，用户按固定官方来源显式取得。开发签名不同runner可变；升级前保留可恢复备份，不承诺覆盖安装。

## 本地 Codex 与下一轮

现在可克隆开发分支并初始化固定子模块，读取 AGENTS、本页、[接管指令](WORK_HANDOFF_PROMPT.md)、[整合契约](VOICEKEY_INTEGRATION.md)与[验收矩阵](VOICEKEY_ACCEPTANCE.md)。已有工作树先检查/保留未提交改动，不强推或重置他人工作。

当前候选交付后，先按[S25集中验收单](S25_DUAL_VOICE_CHECKLIST.md)一次完成真实手机录音、系统权限/外设/焦点、自然语料、冷/热加载、首字/定稿、CER及资源/能耗，记录模型SHA、配置、样本数及P50/P95；再决定默认推荐和修复范围。正式签名/升级、源码授权材料标准化及长期四端/E2EE同步另行完成。缺少手机/证书时不虚构结果或承诺无人执行的后台研发。

[开发计划](DEVELOPMENT_PLAN.md) · [原始本轮证据](evidence/voicekey-runtime-20261106/) · [此前状态原文](STATUS_HISTORY_20261106.md)。

最终专项下载原件、逐文件摘要和严格校验结果见[三API通过证据](evidence/voicekey-runtime-20261106/accepted-model-42227414903/README.md)。
