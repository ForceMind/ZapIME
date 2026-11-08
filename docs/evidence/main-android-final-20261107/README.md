# 主分支完整 Android 最终回读

客户端日期 2026-11-07；GitHub 作业发生于 2026-11-08 UTC。源码 `026bfb8bf467c0d45193a50016e298c3dbb0ac01`，其后 `58a3a54` 只更新文档。运行 [42441322518](https://github.com/ForceMind/ZapIME/actions/runs/42441322518) 已结束，整体 **FAILURE**。这替代此前“仍运行”的快照，旧失败不改写。

| 作业 | 结论 |
| --- | --- |
| Android development APK（含 API28 常规门禁） | SUCCESS |
| API35、API36 native acceptance | 两项 SUCCESS |
| VoiceKey-only API28、API35 | 两项 SUCCESS |
| VoiceKey-only API36 / job128615424140 | FAILURE，准备阶段未进入模型 instrumentation |

API36 原日志先核实系统键盘，再在 `android_offline_uid.py` 的 `adb root` 收到 `device offline` / 非零退出。随后删除本轮唯一共享模型包也失败，原 summary 的 `shared_document_removed=false` 保持。没有 `offline-uid.json`、模型/JNI/Editable 或实际系统 IME 报告，不能算识别、退出或清理通过。只确认失败发生的阶段；暂不推断设备离线的唯一根因，也不把它归于 API35 组字或 API28 恢复问题。

原失败 artifact `13602637340` 已完整下载，1,398 字节，SHA256 `563b05090b8cb7ffeadc0a4188b71f3c849935d93e90d9753582a00a2835be78` 与 GitHub 一致。三份成员按原字节保留，完整安全 job 日志以 gzip 保存，摘要见 `manifest.json`。`workflow-snapshot.json` 是本轮 API 元数据回读；其他成功作业仅核实元数据，未在此记录中声称重新下载/严格复验其全部产物。

源码 Release `v0.1.0-alpha.1` / release438003228 仍公开、prerelease、非 draft，固定 tag/source/三个附件未改动。旧 PR #12/#25/#40 仍是未合并草稿；系统输入失败与缓存研究不能当作已验功能。后续继续发布脚本修复、受限 SAF/组字诊断和冻结质量语料，不移动原标签或覆盖资产。
