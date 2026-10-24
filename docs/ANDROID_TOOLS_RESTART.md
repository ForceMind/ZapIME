# Android 工具面板重启：原始失败与修复边界

2026-10-24，PR #48 的源码 `6a1ea9ef6a619cb5c3fa5ae15d84d24f0a5342e3` 在 Android API36 的原生键盘门禁失败。本报告保留失败现场，并区分可验证的面板重置问题与尚未唯一确定的原始触发；不把后续成功的方法测试抵消为整轮通过。

## 原始证据

- [工作流40103726412](https://github.com/ForceMind/ZapIME/actions/runs/40103726412)，attempt 1，job `120902675009`。
- 实际失败为 `launch: Missing UI node: 返回键盘`，尚未进入本轮拼音上屏断言。
- Artifact `12564147483`，`ZapIME-Android-API-36-acceptance`，922,563字节；实际下载的ZIP与GitHub元数据SHA256一致：`ba98096ef6245e071570f3179f389156a8b686f2d14277ef1d096fa59c7e8dd6`。
- 三张原图、层级、宿主生命周期、失败前服务/Activity状态及ANR/fatal检查见[原始证据目录](evidence/android-tools-launch-40103726412/README.md)。逐文件字节数、SHA和`round-1`一致证明在manifest中。未重绘、裁剪或缩放。

`SmokeInstrumentation`先点击“工具”、匹配“返回键盘”，再保存`tools.png`；但该原图实际仍为普通QWERTY键盘，最终`smoke.png`及层级也没有工具面板。文件名不是已正确绘制面板的证明。

失败前由测试宿主、在`Instrumentation.finish`之前保存的现场显示：`ProbeActivity`处于resumed并有窗口焦点；ZapIME输入视图仍显示；正文为空。服务计数为`starts=3 restarts=1 decision=no_bounded_composition`。宿主日志没有pause/stop，`runtime-errors.log`为0字节，`last-anr.txt`明确记录启动以来没有ANR。后续因instrumentation结束而清理宿主的系统日志，不能冒充失败前失焦根因。

同一作业后续的伴学Service方法、语音与保护材料测试有PASS，但与这次原生键盘失败分开记账，不构成整轮通过。

## 可以判断与仍不确定的内容

本次来源隔离提交仅在存在core commit时改走`trackCoreCommit`；失败时尚未有输入正文，没有证据把面板消失归因于该提交来源路径。

已有实现会在无可保留组字的`onStartInput`中重建会话并强制`layer=letters`，`onCreateInputView`也会回到字母键。额外start/restart与“短暂匹配面板节点，随后回到字母键”的现场相容。**原始失败仍缺少动作与生命周期的精确时间序列**，不能证明重启发生于点击之后，也不能排除触摸重试或无障碍节点时序。它不应被简单标成纯系统基础设施失败，也不能据此认定新输入引擎发生回归。

后续修复针对明确可触发的产品行为：用户在同一普通空编辑器中打开非文本工具面板后，App请求`restartInput`，面板应能继续使用。不是无改动重跑同一失败以挑选一次成功。

## 新候选的范围

新增保留只适用于空preedit、当前为tools、非私密、无资料搜索及相同package/field/type/options的编辑器。`onStartInput`还必须为`restarting=true`；同一编辑器的输入视图重建可保留该非文本面板。新焦点、隐私选项变化以及voice/data/search不进入这条保留路径。

`onStartInput`保留工具面板时，仍重建输入引擎、推进输入epoch并开始新的学习session，不复用旧正文、旧学习片段或异步结果。单独重建输入视图则继续原有视图流程，在保留显示面板的同时暂停伴学。组字继续走原有的快照/选区验证路径；本次新增面板保留不扩大组字恢复条件。

诊断仅保留最近16条单调uptime毫秒与固定面板事件码；测试驱动保留最近64条固定触摸尝试/结果，测试宿主的既有长度/选区事件加上相同单调时钟。新增产品事件不记录真实按键正文、包身份、令牌或学习文本，也不增加编辑器全文读取；原有系统dumpsys字段与专用合成测试现场单独保留。

## 验证要求

新增原生测试通过独立App的真实EditText请求`restartInput`，并等待新served连接收到实际IME命令，再要求工具面板的“返回键盘”仍存在、正文保持空白。随后更改`IME_FLAG_NO_PERSONALIZED_LEARNING`并再次重启，必须显示隐私状态、退出工具面板且正文不变；恢复普通选项后，实际打开并关闭工具面板，再继续原来的拼音/焦点/隐私/资料/语音与真实服务退出门禁。

旧断言不删除，不以方法探针替代系统输入。新候选仍须由API28/35/36完整CI执行；本证据包不预先宣称这些新测试通过。后续事件记录用于区分重启、视图重建、重复触摸与节点时序，不能倒推成旧失败已经获得唯一根因。也不把本修复称为#38全部快速输入或间歇问题已解决。

## 后续实际验收（2026-10-25）

最终源`ca0dc35c1fcfc2bb66573a5882f1b178f4c6744b`的[Android40108971495](https://github.com/ForceMind/ZapIME/actions/runs/40108971495)已完成API28/35/36全部原生门禁，含每API两轮同编辑器空工具面板重启、隐私选项变化及原有真实正文/语音/服务退出断言。#48已合并`347a995`，具体任务#50验收关闭；#38其它快速输入/间歇问题仍开放。

[API36成功原始证据](evidence/android-voice-touch-success-40108971495/README.md)保存两轮日志、真实工具重启原图、独立语音结果及服务退出证据。成功路径没有产品面板事件dump；本次通过不补足旧失败缺少的动作时间序列，不证明旧失败唯一原因，也不代表真机/自然语音/翻译功能完成。
