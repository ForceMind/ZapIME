# #95 工具触摸候选：依赖下载失败原始记录

本目录只记录已结束的运行，没有启动重跑或修改代码。#95固定head `685a3a59b09fb4791a9de89afd00ca8eae97f672`、tree `44a8eac9b45215d26b0966bf1f44c9b34dd41b5c`；三份构建/主机原日志实际checkout均为 `9940b9c8e242f5d231166fbe422df66f2bc96280`。GitHub git/commit元数据独立回读确认checkout与head完整tree相同。工具宿主blob `2a558adb3ba9578e090185e41df940f294c03c11` 与本地已编译候选相同，不能因此补记模拟器执行成功。

## 实际首失败

| 运行 / job | 原失败阶段 | 已得到与未得到的结果 |
| --- | --- | --- |
| [完整Android42464091047](https://github.com/ForceMind/ZapIME/actions/runs/42464091047) / 128679477387 | Offline candidate context, bounded worker and pinned model resources | 14项资源单测与catalog先通过；prepare_candidate_model.py下载HTTP504，未构建APK或运行其内API28键盘门禁 |
| [候选专项42464091021](https://github.com/ForceMind/ZapIME/actions/runs/42464091021) / 128679477444 | Check model provenance, resources and worker lifecycle | 相同资源单测与catalog通过；prepare_candidate_model.py下载HTTP504，未构建候选APK |
| 候选专项 / 128679477234 | Frozen synthetic paired quality and host resource baseline | native15组、JNI36项与独立oracle实际通过，7项质量runner单测通过；随后fetch_resources.py词库下载HTTP504，未执行质量语料 |

HTTP504发生在外部依赖下载。原日志未证明响应由哪个服务器或代理生成，也未标明当时失败的具体模型成员；不据此声明唯一网络根因。原失败没有被后续单测或旧运行成功覆盖。

完整Android的API35 `128679990655`、API36 `128679990637`均在下载 `ZapIME-Android-development` 时报告artifact不存在；VoiceKey API28/35/36同样缺少构建输入。这些是原构建未产出附件后的下游失败，不是实际设备root、工具触摸、组字或语音执行的结论。候选API矩阵为SKIPPED。两个workflow最终均为FAILURE，完整Android没有任何可下载的输出artifact。

## 已下载的有限主机原件

唯一候选主机artifact `13611450772` 已实际下载并复核：2,639字节，SHA256 `41c20cee555fcd76ed9275317b87ba8fe790d1af0d77d675b642032d443f85f1`。`host-partial-original.zip` 经成员白名单检查，只含三份JSON：`native-report.json`、`jni-report.json`、`oracle-report.json`。每份另以mtime=0 gzip保真保存，已逐字节核对ZIP成员；没有 `quality-report.json`、APK、模型权重或音频。

三份报告已严格复核真实model/runtime/probe摘要一致性、native15组/JNI36项、独立oracle两次图执行、session关闭和0.001误差门槛。它们只证明该主机数值/JNI接缝；不能代表词库质量、Android系统IME、工具物理入口或ARM/S25。

## 文件与边界

- `source-metadata.json` 保存精确源/checkout、最终job状态、artifact摘要及没有自动重跑的记录。
- `strict-failure-summary.json` 区分各个首失败、下游缺失输入和未运行门禁。
- 五份原始job日志以mtime=0 gzip保存，`job-log-provenance.json` 同时记录原始文本字节数/SHA和gzip SHA；未显示或保存临时下载URL。
- 本目录仅包含安全metadata、report、日志及摘要。没有APK、权重、真实输入、录音、剪贴板、资料库、恢复码或密钥材料。checkout授权在原GitHub日志中已为掩码。

工具fixture的新物理断言在这一源码没有执行。原本机Java编译、旧系统IME成功及其他分支的候选报告都不能代填。本记录独立提交，保持正在验证的产品源码不变。
