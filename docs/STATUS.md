# ZapIME 项目执行状态

## 默认开启的本机候选增强（2026-11-07）

按用户要求，在独立 `feat/offline-candidate-ranking` 分支从手动同步基线 `f727d2031f438881fbfaf99aa474517cde66cd29` 接入 **MSIME + chinese-ime-lm rerank-v2 8M + 既有 ONNX Runtime**。Android 设置缺省开启，固定模型随开发包准备，工作线程实际执行模型并只返回既有完整候选排列。个人置顶、部分上屏、九键读音和显示时候选 ID 保留；私密/禁止学习/搜索字段排除，资源/推理失败沿用基础候选。[实现与范围](CANDIDATE_RANKING.md)、[ADR-024](DECISIONS.md)及资源来源记录已经落盘，vendor 固定版本未改变。

本地主机：完整工作区 **134 项**测试、fmt、严格 Clippy 通过，其中新增 **16 项**核心候选回归；Android Python **89 项**、固定资源 **14 项**、三组 JVM 上下文/worker/真实资源检查通过。实际双 ABI 开发 APK 已用最终精确读音/可见页/诊断 revision 代码重建，通过 JNI、固定模型哈希、许可证、16 KiB 和签名门禁。两个独立 Android harness 均已编译。真实 8M 主机推理与上游 Python feed 的 **15 组**数值/缓存检查、生产 JNI/JVM **36 项**及生产词库 **3 组**实际排序提交通过；普通页最大误差为 0，长自然句页有界分批最大均值 logP 差异约 0.03533。发现的过度补全与可见候选挤出问题已按原引擎完整读音及最终可见槽修复，[原问题与修复证据](evidence/candidate-ranking-20261107/README.md)分别保留；这些不是输入准确率或 Android 性能指标。

实现 `3a11177b7d0b2ef054183f5297a282dcb650a14f` 已保存于 [PR #78](https://github.com/ForceMind/ZapIME/pull/78)。共享 Linux/macOS/Windows 与 Apple 宿主 CI 成功，主机真实模型/JNI、双 ABI 产品构建成功。首轮候选 [42358906839](https://github.com/ForceMind/ZapIME/actions/runs/42358906839) 的 API28/35/36 在第 3 项严格数值比较失败：Android ORT 1.28.2 三端输出一致，但相对 Linux 1.24.4 原参考最大差约 0.09153，超出 0.001；[原失败与包身份](evidence/candidate-ranking-20261107/android-first-run-42358906839/README.md)保留，不把首选相同写成通过。系统 IME 因前项失败尚未执行。

当前仅修 test-only 参考与 suite：从固定上游 feed builders 冻结输入张量，用同一 APK 运行库独立两次图运行作为参考，再与生产 native 比较；不引用 Android 实测分数作期望值，不放宽 0.001、不减少 **14 项**。方法与真实系统 IME 各跑一次，任何失败仍使 suite 失败。新测试须等固定提交 Android CI，不将主机编译或 schema 验证代填。真实键盘门禁要求生产词库、触屏按键、当前 inputRevision/appliedRevision 相等且发布计数增加、所见候选精确上屏及恢复系统键盘。其他三端模型宿主、25M 对比、真实语料准确率、S25 延迟/内存/能耗、正式签名/稳定发布和训练数据声明差异的最终澄清仍未完成。现有语音、同步、备份及系统输入门禁继续保留，不用候选单项结果代替。

测试修订 `a73eea01` 的 [42360463367](https://github.com/ForceMind/ZapIME/actions/runs/42360463367) 已实际证明 API35 的 **14 项方法检查通过、同运行库独立参考误差 0**。真实系统 IME 也完成默认开启、生产词库/模型、`shijie` 触屏输入、当前 revision 排序发布及点击“世界”进入宿主 Editable。该 job 仍明确 **FAIL**：最后恢复含分号的系统输入法设置值时，test-only adb shell 命令未转义而 exit 127；其余清理通过。[原 ZIP、报告、包身份与限制](evidence/candidate-ranking-20261107/android-second-run-42360463367/README.md)保持原样。当前只修恢复命令的远端 shell 编码，保留读回/清理门禁，设备复验仍需新 CI，不修改产品实现。

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
