# 三API模型闭环与API36真实IME启动失败

固定源码 `c56b3cbcaf4a80a28ee99b091229485be0aaf490`，[focused run 42224836288](https://github.com/ForceMind/ZapIME/actions/runs/42224836288)。产品源码与08e849相同；本次只修改测试事件，显式FINGER及一次真实触屏。结果按层级分别记，不将整个工作流称为通过。

| API | Job | 模型/确认/计时探针 | 后续真实系统IME |
| --- | --- | --- | --- |
| 28 | 127916784053 | 14主、23 Editable、JNI boundary、watchdog全部PASS | VoiceKey录音/停止/重录/取消/焦点/光标/隐藏、系统键盘切回及真实退出PASS，job SUCCESS |
| 35 | 127916784017 | 同上全部PASS | 同上真实流程PASS，job SUCCESS |
| 36 | 127916784187 | 同上全部PASS | 初始显示阶段失败，`Missing UI node: ⚡ 闪电 · 离线`；未执行后续语音流程，job FAIL |

三API全部真实完成官方包/成员固定SHA的SAF导入，X-ASR模型缺席、VoiceKey明确选中，provider确实返回App；实际事件均记录TOOL_TYPE_FINGER=1、source=4098、一次物理选择。原provider前后PNG保留，没有盲重试或绕过文件选择器。

| API | produced=accepted样本 | 非空partial次数 | 首partial ms | producer EOF→final ms | 实际Handler watchdog ms |
| --- | --- | --- | --- | --- | --- |
| 28 | 125861 | 4 | 1101 | 1764 | 30011 |
| 35 | 125861 | 2 | 1400 | 1673 | 30016 |
| 36 | 125861 | 3 | 2300 | 2022 | 30015 |

这些时长只描述一条固定合成PCM、相应x86_64模拟器及本探针起点，不含用户按键、模型校验/初始加载，也不是自然语音、ARM手机端到端延迟或P50/P95。watchdog是受控VoiceCapture状态、实际30秒Android主Handler和真实模型idle-worker取消/退出；不冒充active matrix、AudioRecord或系统IME的超时验收，JSON保留该范围。

API36的失败前诊断显示宿主已获得焦点、show request sent=true，ZapIME服务有 `mInputStarted=true`，但 `mViewsCreated=false`、`mInputViewStarted=false`、`mShowInputRequested=false`；系统报告 `PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE` 失败。此记录只陈述原观察，不将它推断为模型失败，也不通过跳过显示/放宽断言消除失败。真实失败的hierarchy、smoke图、host lifecycle及服务/窗口/系统诊断都已保留。

每个API原ZIP已下载并独立重算大小/SHA，与 `github-artifact-metadata.json` 的digest/source逐项匹配；下载后使用该源码的 `verify_reports`、`verify_watchdog_report`、`verify_boundary_report` 三验证器重新检查，三API的模型/确认/计时报告全部通过。`provenance.json` 保留所有原报告、图像及原job日志的实算摘要。模型权重、PCM/WAV、APK、用户数据及临时签名下载URL均不在本目录。
