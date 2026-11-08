# 空编辑器工具按钮触摸与配置重建时序

本目录先保留首次失败，再修复独立测试入口。它不是生产组字修复或真实设备通过证明。

PR #87 head `cbe55e286b017512212efc1d07e7db13c3112403`，实际checkout `38a58fdab216ed4fa0d1abeb2da3a565a191a904`，tree均 `3571ab7ebb16f12bff6067c3e121f6414169e032`。[完整Android运行42459746972](https://github.com/ForceMind/ZapIME/actions/runs/42459746972)的API36 job `128670143758`首轮失败于 `tools_open / Missing UI node: 返回键盘`。该完整运行仍为FAILURE；API35常规及三端VoiceKey成功、同API36后续独立语音native/UI成功均不替代键盘失败。

原artifact `13610258131` 已实际下载：825,406字节，SHA256 `ac42c6299ae6240826a4e90666b9bc44e9ed8f9c77a99cbc4eed13d783601974`。完整ZIP在独立工作现场保留；本目录提交的是 `safe-originals-manifest.json` 明确列出的24份原始成员，以mtime=0 tar/gzip保真封装并逐字节复验，不把它称为完整ZIP。重复round-1成员及其余原artifact清单另由 `archive-member-manifest.json` 记录。未将合成PCM、模型或不相关备份页面报告纳入本目录。

`ci-*`、宿主生命周期和hierarchy由test-only宿主在Instrumentation.finish之前保存；其余外层系统dump按原文件名保留，不能冒充失败瞬时状态。原始有限时序：

| uptime ms | 原始事件 |
| ---: | --- |
| 86657 | driver记录 tools_touch_attempt |
| 87343 | 产品 INPUT_RESTART/INPUT_REBUILT，epoch3→4，decision=no_bounded_composition |
| 87372 | 新键盘 view_letters；整个环未观察到 tools_open |
| 87424 | driver记录 tools_touch_sent，仅说明注入返回ACK |
| 87430 | 独立宿主收到finish/batch-end，实际Editable仍为空 |

系统input_method历史另有08:20:27.998 `ORIGIN_IME / RESET_NEW_CONFIGURATION`。这是配置重建与工具触摸重叠的证据；旧泛型click没有实际DOWN/UP派发时序，不能据ACK认定按钮action发生或断言唯一产品根因。工具面板没有实际打开，所以仍让原后置断言失败。它与 `sh/shi` 组字、候选推理及语音质量是不同门禁；本次空编辑器没有执行组字，模型submitted/scored也为0。

`strict-failure-summary.json` 区分首失败、随后独立语音成功与实际观察到的退出。已验证系统键盘切换、ZapIME服务消失、无产品runtime fatal；没有用缺少结构化原值读回的键盘报告宣称全局设置全部恢复。原始job日志、源码身份和摘要均保留。

本地修复基线是已含上述诊断代码的 `449dd25`，测试修复之后须运行新源码完整Android回归；编译或源码审查不替代API28/35/36、更不替代S25。原失败不会因后续成功而改写。
