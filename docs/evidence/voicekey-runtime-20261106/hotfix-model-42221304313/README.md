# 有界背压修复后的首次 Android 模型闭环

固定源码 `08e84980562ecc8fab436ffcdab6e5aee8c567e7`，[focused run 42221304313](https://github.com/ForceMind/ZapIME/actions/runs/42221304313)。同源结果分开记录，整体工作流仍因现代文件选择未完成而失败，不称三API或手机验收通过。

| API | Job | 实际结果 |
| --- | --- | --- |
| 28 | 127905118355 | **SUCCESS**：14个模型主案例、23项生产确认事务/真实Editable、实际30秒Handler/模型idle worker watchdog、VoiceKey真实系统IME与模拟器静音AudioRecord生命周期、系统键盘切回/实际退出通过 |
| 35 | 127905118342 | **FAIL**：`official document provider did not return after one selection`；未完成模型导入，未执行模型推理或后续IME |
| 36 | 127905118174 | **FAIL**：与API35相同的provider返回失败；不以API28结果代填 |

API28实际核验了官方原包及两个固定成员SHA，且X-ASR模型不在安装状态。独立绝对时钟合成producer复用生产602帧VoicePcmQueue；原125490样本及371个零尾样本全部生产、接受，共125861，无丢帧；最大producer迟到1ms。收到2次非空partial，首个1401ms；producer EOF到final为1656ms。这些只是这一个模拟器/合成样本的观测，不是自然语音、ARM手机P50/P95或同机引擎胜负。

`voicekey-watchdog-report.json` 的真实Android主Handler回调为30024ms，只触发一次，最终文字为空、finish拒绝cancelled且物理idle worker退出。其受控VoiceCapture字段/实际stop()/真实计时和模型native会话不等于active matrix计算、AudioRecord或系统IME的超时场景；范围保留在JSON中。其余真实系统IME录音生命周期由单独UI instrumentation验证。

API28 JSON已额外使用本次源码的 `verify_reports` 和 `verify_watchdog_report` 在下载后复核，14主/23 Editable/共同EOF时限/权限模拟范围全部通过。现代的单次物理选择被报告但provider未返回；不把动作ack或后续驱动变更当成功。

各API目录包含原始artifact文本/JSON及已有合成UI截图、原job日志。`provenance.json` 与GitHub metadata记录原ZIP实算大小/SHA/source以及每个保存成员/日志摘要，全部重新校验。没有模型权重、PCM/WAV、APK、用户数据或临时签名下载URL。
