# 第二轮旧驱动 VoiceKey-only 失败原件

来源 `c7b98ebb7a0707ea622a6799a8d1d0246751e759`，Android [run 42213679453](https://github.com/ForceMind/ZapIME/actions/runs/42213679453)。本轮已包含确认前权限检查和23项真实 Editable 用例，但 VoiceKey 专项仍使用原先的文件选择驱动及简略错误报告。此目录保留失败，不替代后续诊断。

| API | Job | Artifact | 实际失败 |
| --- | --- | --- | --- |
| 28 | 127884184094 | 13500604089 | `system SAF import` AssertionError；尚未完成导入 |
| 35 | 127884183974 | 13501166683 | `system SAF import` IOException；失败后诊断仍有 DocumentsUI PickActivity，不能把点击返回成功视为已导入 |
| 36 | 127884183959 | 13501251532 | 固定模型导入、仅 VoiceKey 选择及7项 PCM/取消通过，`actual synthetic decode` AssertionError；整体失败 |

每个目录保留下载后原始诊断 JSON/TXT 及原作业文本。原 ZIP 的字节/SHA 已独立重算并与 `github-artifact-metadata.json` 比对；`provenance.json` 记录源码、每个成员和日志的摘要。模型、音频、APK及临时签名下载 URL 均未保存。

原探针没有最深断言原因，不能据本轮断言模型不出字、推理背压或权重损坏，也不把后续诊断/驱动修改本身计作通过。前三 API 的阶段差异原样保留。真实系统 VoiceKey IME/AudioRecord 与最终确认阶段未执行，不借 X-ASR 的结果代填。
