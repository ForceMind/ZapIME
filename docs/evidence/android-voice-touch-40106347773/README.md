# API35/36 语音入口触摸失败原始证据

源为PR #48源码`8a612c888ca9e288c0ce7673a6f4d406abda1074`、run `40106347773` attempt 1。两个独立下载的ZIP均按GitHub元数据的精确大小和SHA核验后读取，不只引用远程状态。

`api-35/`与`api-36/`各保存12份原文件：

- `smoke.png`：最终语音UI失败现场，原始320×640像素，普通字母键盘与合成正文仍在；没有重绘/裁剪/缩放。
- `host-lifecycle.txt`、`ci-input-service.txt`：原始uptime、驱动触摸确认和产品16条有界面板事件；第二次触摸确认之后没有工具按钮动作，也没有新的start/view事件。
- `hierarchy.txt`、`ci-activity-state.txt`：失败现场层级和Activity状态。
- `ui-instrumentation.txt`、`combined-gates.json`：语音UI失败与主键盘门禁成功分别记账。
- `native-keyboard-round-1.log`、`native-keyboard-round-2.log`：两轮完整主键盘原始PASS记录，不能抵消独立语音失败。
- `voice-native-methods.log`：原始独立JNI/方法验证记录，不冒充语音入口UI成功。
- `last-anr.txt`、`crash.txt`：原始ANR/fatal检查；空fatal文件保持0字节。

`manifest.json`记录两份ZIP的artifact/job/字节/SHA、源码SHA、每个原文件在ZIP内的位置和逐文件字节/SHA。文件原字节复制，命名只为了明确来源，没有改写内容。它们全部来自专用GitHub模拟器与合成测试，不含用户真实聊天或剪贴板数据。

具体坐标错位仍为推断，原始驱动没有记录bounds。公开框架同步机制、测试驱动修正及尚待运行的验收见[报告](../../ANDROID_VOICE_TOUCH_TIMING.md)；新CI结果不在本目录预记。
