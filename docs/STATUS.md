# ZapIME 项目执行状态

更新时间：2026-11-06 23:05 PST（America/Los_Angeles）。本轮已实际修改产品、运行测试、推送代码并下载核验 APK。累计汇报次数及此前汇报间隔没有可靠连续记录，不补造。

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
