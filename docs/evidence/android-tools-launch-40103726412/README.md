# API36 启动工具面板失败原始证据

来源为PR #48源码`6a1ea9ef6a619cb5c3fa5ae15d84d24f0a5342e3`、run `40103726412` attempt 1、job `120902675009`。独立审查者实际下载artifact `12564147483`，按922,563字节及GitHub公布的SHA256验证ZIP后读取；不是只引用远程元数据。

- `keyboard.png`、`tools.png`、`smoke.png`：三张320×640原图；命名为tools的图实际显示普通字母键盘。原字节复制，没有重绘/裁剪/缩放。
- `hierarchy.txt`、`host-lifecycle.txt`：失败时的原始层级与独立测试宿主事件。
- `ci-input-service.txt`、`ci-activity-state.txt`：测试宿主在`Instrumentation.finish`之前采集的服务/Activity状态；当前IME可见，测试App仍在前台。
- `last-anr.txt`、`runtime-errors.log`：原始ANR检查与0字节fatal日志。空日志作为原始文件保留，不代表完整输入门禁通过。
- `workflow-job-excerpt.log`：GitHub解码作业日志中的连续原始选段，包含launch失败与后续独立方法测试PASS；不是完整日志。
- `manifest.json`：artifact大小/SHA、精确源码与run/job、每个文件的大小/SHA、PNG尺寸及当前目录与`round-1`原文件逐字节相同的核验结果。

这些文件全部来自专用GitHub模拟器与合成测试应用，没有用户聊天或剪贴板正文。文件时间是采集/归档时间，不能用于补造未记录的工具点击与重启先后顺序。诊断、修复限制与新测试要求见[报告](../../ANDROID_TOOLS_RESTART.md)；原始启动根因未唯一确定，不把候选新CI预先记为通过。
