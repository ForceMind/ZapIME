# 固定 ORT 依赖恢复与 Android 验收收口

核验日期：2026-11-19（America/Los_Angeles）。这是已完成的固定源码验收；后续合并与文档提交通过源码树核对，不冒称重新执行了本轮 CI。

## 精确源码与集成身份

| 身份 | 值 |
| --- | --- |
| 原 main | `e2877a85ff2eb3b9eeba8eded83cdd014d5a0ebf` |
| PR #122 头文件恢复 head | `8865c969ef364a671e063e4a853d9e7328ed1815` |
| PR #123 最终验收 head | `60beadee4af411541fc716fbb3bc0eb74b769591` |
| 八条工作流的实际 checkout | `95d93e4b6fc2dbd51cb4e2446d705c54a9166f33` |
| 已验 head / checkout 的完整 tree | `d4b891619c7a162e0d60db230bfadaed2b419c84` |
| 功能整合进入 main | `c9207de739d6d1872502694826a69b8d29d4540e` |

Git commit API、八条工作流各至少一个真实作业的 checkout 日志、开发包原构建日志及下载元数据相互核对。整合前后的完整 tree 身份见 [integration.json](integration.json)；本地提交元数据不同但同树的事实也单独保留。验收来源没有用后来的文档 SHA 代替。

## 完整 CI 最终结果

固定来源的 **8 条工作流均在 attempt 1 成功**；29 个 job 中 28 成功、1 个源码发布 job 按条件跳过。本轮未重跑，没有抹除 PR #122 和更早的失败。

| 工作流 | 实际运行 | 结果 |
| --- | --- | --- |
| 完整 Android 原生输入 | [44534343888](https://github.com/ForceMind/ZapIME/actions/runs/44534343888) | SUCCESS |
| VoiceKey Android focused | [44534343569](https://github.com/ForceMind/ZapIME/actions/runs/44534343569) | SUCCESS |
| 离线候选质量与 Android 验收 | [44534343576](https://github.com/ForceMind/ZapIME/actions/runs/44534343576) | SUCCESS |
| 共享开发检查与 Apple 构建 | [44534343713](https://github.com/ForceMind/ZapIME/actions/runs/44534343713) | SUCCESS |
| Windows TSF 开发检查 | [44534343962](https://github.com/ForceMind/ZapIME/actions/runs/44534343962) | SUCCESS |
| 离线语音 CPU 探针 | [44534343916](https://github.com/ForceMind/ZapIME/actions/runs/44534343916) | SUCCESS |
| 独立密文同步服务 | [44534343620](https://github.com/ForceMind/ZapIME/actions/runs/44534343620) | SUCCESS |
| 源码准备 | [44534343744](https://github.com/ForceMind/ZapIME/actions/runs/44534343744) | SUCCESS；publish SKIPPED |

完整 run/job/步骤/artifact 的原始字段精简投影在证据包的 `ci-snapshot/github-api-projection.json`；它明确不是完整 API 响应。日志文件明确标记为保留原行的摘录。PR #123 的[自动代码审查](https://github.com/ForceMind/ZapIME/pull/123#issuecomment-7010611726)针对同一 head 完成，无 findings；没有将其称为独立安全审查或正式 review approval。

## 三个原阻塞点的实际结果

### 原锁定依赖恢复

PR #122 将三个 ORT C 头文件和 MIT 原件随源码保存，全部匹配恢复前的大小及 SHA。PR #123 找回原 Linux 诊断归档，只增加唯一显式 `npmmirror-recovery` 运输选择；默认官方来源、原信任锁、TLS、成员校验、版本与 ABI 保持原契约，没有自动切源。

原归档 SHA256 为 `d7209b8751b27b862b0c76332c2e20e203396edb5dab700ecf4bb485cf147415`；实际加载并执行评分的 Linux 1.28.2 库为 `088f24b1fc56714d3efaaeb3ac2ee486a5d7b50ccfbb3dd26fd1a612534a05fb`。真实固定 v3 主机质量评测完成，64 条有上下文样本首选 38 → 50、14 改善、1 退步、5 缺失；与原 1.24.4 对照的质量指标一致。空上下文诊断仍有 Top3 55 → 53 和4条排序退步，原报告保留；产品的空上下文回退契约没有改变。这是合成主机结果，不是手机准确率、延迟或能耗结论。

官方旧 URL 的不可用原因仍未证实；恢复原字节并通过构建不等于查明上游组织或安全事件原因。原请求和失败记录见 [头文件来源恢复](../ort-source-recovery-20261119/README.md) 与 [Linux 归档恢复](../ort-host-recovery-20261119/README.md)。

### API36 工具面板及完整后续输入

原失败截图、节点层级与 `restart_keep_tools/view_keep_tools` 事件不足以直接认定产品关闭了工具面板；失败附近还发生系统配置重启。本次只修改独立测试的观察，要求原宿主与公开动画状态采集前后一致，同一 IME 窗口的两个工具节点连续稳定；缺失证据重新证明或在固定总预算内失败，真正字母布局或宿主改变立即拒绝。原空正文、隐私切换、返回键盘、组字和退出断言保留。细节及未修改的原失败在 [观察器修复](../tools-panel-observation-20261119/README.md)。

新 [API36 job 135606193452](https://github.com/ForceMind/ZapIME/actions/runs/44534343888/job/135606193452) 两轮完整键盘均 PASS，随后 X-ASR 原生/确认/UI 与真实服务退出通过。该严格工具检查是两轮流程的必经路径。最终宿主事件为有界环形记录，早期采样事件已不在两轮最终日志内，因此不报告原件未保留的实际样本次数或毫秒值。独立纯报告复验的精确范围、脚本与输出保存在证据包 `full-api36/`，它们不重新运行设备。

该结果不关闭 Issue #38 的所有历史间歇问题，也不替代 S25 或其他实体设备验收。

### focused API35 的 ADB 环境失败

本轮 focused API35 与完整 Android 的 VoiceKey API35 均通过；focused 原件显示本次 `adb root` 第一次成功，并实际执行 SAF/JNI/Editable、系统 IME、模拟器录音、服务退出、共享文档和键盘策略恢复。旧 `device offline` 失败继续作为原运行的失败保留；没有声称找到或修改了新的产品根因。

## 开发包的来源与签名范围

[本次开发附件](https://github.com/ForceMind/ZapIME/actions/runs/44534343888/artifacts/14535683353)已完整下载核对，248,782,304 bytes，SHA256 `9a5ccd600a71af3e499c389f46ec62ad5f93c61a72f636bb13e0605a5e983e9b`。

| 产品 APK | 字节数 | SHA256 |
| --- | ---: | --- |
| arm64-v8a | 58,778,567 | `ed0dfbb94342aaef85cbbdf01020db9277616e807e5fcc3af7d0d79506147063` |
| x86_64 | 60,089,562 | `756e55553afe7983689d9298f63edea8f86a1bfdc6e820a2e66ad659a5754dfb` |

两份原始 APK 哈希与同源构建日志一致，现有静态打包检查通过；包内证书均为 `CN=ZapIME Development`，原 CI 的 v3 签名验证通过。本地没有再次执行 APK 或原生库，也未重新运行 apksigner。没有单独的包内 build-metadata.json，来源链使用实际 GitHub 元数据、Git tree 和原构建日志，不伪造内嵌元数据。

产品 APK 内置原固定8M候选模型；X-ASR/VoiceKey 声学权重不在产品 APK 内。外层附件另含独立 X-ASR 模型归档和 CI 合成 PCM，不能把产品包范围扩写为整个附件都不含这些内容。详细逐项校验与边界在 `development-apk/verification.json`。

这是开发签名与模拟器验收的开发包；正式签名、可恢复升级、自然语音及 ARM/S25 长期资源测试仍待完成。原 `v0.1.0-alpha.1` 发布计划、标签及三个公开附件没有改变。

## 证据包与复现范围

[accepted-reports.zip](accepted-reports.zip) 保存选定报告的原始字节、原完整 ZIP 的成员清单、GitHub digest、同源身份、纯报告复验及开发包静态核验。每个收录文件的大小/SHA 在 `included-files.json` 中；源 artifact 的全部成员清单不表示这些成员都被收录。APK、模型、PCM、私钥或凭据不进入此小型证据包。归档 SHA 在本目录 `SHA256SUMS`。

关键源 artifact：host `14535004877`、focused API35 `14536117182`、完整 API36 `14537791631`、开发包 `14535683353`。这些 ZIP 均实际下载，大小和 SHA 与 GitHub 相符；其中报告类附件可能早于开发包到期，故本目录保留选定原始证据。


独立复验脚本 `full-api36/reverify_api36.py` 的完整重放需要原 artifact ZIP 及全部91个解压成员，并使用对应哈希的仓库源码；本精简包保留选定报告和已完成的完整复验记录，没有声称能够重建原始ZIP。原附件内的合成PCM及广泛截图/运行时诊断未收进本包。

下一独立工程切片仍为 Issue #77：把真实 HTTPS 认证终止页接入已有首次发布安全 API，先完成真实服务闭环，再单独接 Android 明确选择与确认。已有 JNI、只读状态页及安全队列不重做。离线 LLM、四端完整系统输入、配对/冲突/历史密钥和真机/签名目标继续按原计划验收。
