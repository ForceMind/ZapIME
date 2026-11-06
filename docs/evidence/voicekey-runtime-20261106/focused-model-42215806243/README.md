# 精确诊断首次专项失败

源码 `45d9057b19f78f8b35599cfe1c1b19a50f493184`，独立 [run 42215806243](https://github.com/ForceMind/ZapIME/actions/runs/42215806243)。本轮真实失败原件保留，后续修复不覆盖这些结果。

| API | Job | 观察 |
| --- | --- | --- |
| 28 | 127888186708 | 首次物理文件名触屏已执行，但 `SAF import completion` IOException；provider未真正返回，不能当作导入完成 |
| 35 | 127888186672 | SAF固定模型导入、仅VoiceKey选择及7项PCM/取消通过；JNI `voicekey_audio_backpressure`，已接受68800样本，已得到1次非空partial（1907ms、2字符，仅保存摘要） |
| 36 | 127888186660 | 同模型/PCM准备范围通过；JNI `voicekey_audio_backpressure`，已接受62400样本，尚无非空partial |

这次精确错误证明在100ms同步喂入的正常模型推理期间遇到了生产内部队列背压，不能改称模型缺失或所有平台不出字。三API均整体失败；确认事务与后续VoiceKey系统IME录音流程未执行。自然语音及手机性能也未执行。

每个API目录是下载后原始诊断JSON/TXT及原作业文本。`provenance.json` 保存独立重算的ZIP和所有文本成员大小/SHA，与 `github-artifact-metadata.json` 的GitHub digest/source一致。模型、音频、APK及临时签名下载链接均不在本目录。

后续变更将稳定观察后的文件名物理选择、provider真实返回、独立绝对时钟生产者、生产VoicePcmQueue、共同EOF时限及可取消背压等待分别验证。后续实现本身不算修复通过；保留同一合成PCM、全部样本、非空partial/final及13主案例、23项真实Editable断言。
