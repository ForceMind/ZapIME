# 第一轮 VoiceKey-only Android 专项失败原件

来源：`5bbfe310e716762b36cdb0cf28b09ad5a2e1818c`，Android [run 42211989441](https://github.com/ForceMind/ZapIME/actions/runs/42211989441)。这里保留第一轮失败，不因后续修改而覆盖。全部来自独立临时 GitHub 模拟器，没有用户录音、个人资料或模型权重。

| API | Job | Artifact | 实际结果 |
| --- | --- | --- | --- |
| 28 | 127877569157 | 13500516595 | fresh profile 通过；`system SAF import` AssertionError；没有完成模型导入或 JNI 解码 |
| 35 | 127877569178 | 13499897710 | 实际 SAF 固定模型导入、仅 VoiceKey 选择、无样本/零/有效前缀/±1 PCM 与跨线程取消通过；`actual synthetic decode` AssertionError；整体失败 |
| 36 | 127877569080 | 13499388813 | 与 API35 相同范围通过；`actual synthetic decode` AssertionError；整体失败 |

每个 API 目录包含下载后未修改的 JSON/TXT 诊断成员，以及通过 GitHub workflow-job logs API 取得的原始作业文本。`provenance.json` 保存原 ZIP 的独立实算大小/SHA256、每个诊断成员及日志的摘要。原 ZIP 不含权重；仓库保留文本诊断，不额外保存二进制 ZIP。没有提交临时下载链接。

API28 的 `activity-top.txt` 显示真实 Downloads 文件格已出现，但文件 item 与祖先没有 clickable 标记，原驱动仅使用无障碍点击，因此该路径无法选择文件。已新增仅针对当轮观察到的可见文件名的一次真实触屏路径；需要后续 CI 证明，不能把驱动变更本身记作通过。

API35/36 的首次探针只保存异常类型，未保留最深固定断言原因，不能据此断言是模型不出字、背压或其他问题。后续探针细分 native start/feed/tail/preview/final，逐帧保存已接受样本数、partial 数/首个非空预览、摘要及安全 JNI 错误码；保留原断言和 100 ms 馈入节奏，等待新的真实结果。原模型与音频均未被更换，失败也未删除。

各作业均执行系统输入法切回、服务退出、崩溃/ANR 读取及本轮 Download 文件清理。对应 `offline-uid.json`、`ime-teardown.txt`、`crash.txt` 等原件保留其实际结果。专项在合成解码阶段失败，因此后续原确认事务和真实 VoiceKey 系统 IME/AudioRecord 生命周期未执行，不能借原 X-ASR 门禁代填。
