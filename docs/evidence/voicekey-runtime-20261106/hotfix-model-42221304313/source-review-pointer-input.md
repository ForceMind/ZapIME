# 文件选择测试事件的工具类型：来源审查

核验记录：2026-11-06 UTC。此记录解释在 `08e84980562ecc8fab436ffcdab6e5aee8c567e7` 的首次完整 API28 模型/IME 成功及 API35/36 provider 未返回之后，为什么修改后续**测试驱动事件**。它不是现代平台已经通过的证明，也未改动该产品实现。

| 一手来源 | 实际取得范围与结论 |
| --- | --- |
| [AOSP MotionEvent.java](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/MotionEvent.java) | 已打开并定位简化 `obtain` 的 pointer 初始化：先 clear，再指定 id，未指定 toolType；PointerProperties.clear 将其设为 UNKNOWN。设置 source 为 TOUCHSCREEN 不会把 toolType 改为 FINGER。 |
| [Android MotionEvent API](https://developer.android.com/reference/android/view/MotionEvent) | 公共多指针 obtain、PointerProperties、PointerCoords、TOOL_TYPE_FINGER 与 getToolType/getSource 是此测试构造并验证事件所用的接口。 |
| [现代 AndroidX SelectionTracker.java](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/recyclerview/recyclerview-selection/src/main/java/androidx/recyclerview/selection/SelectionTracker.java) | 官方搜索缓存片段表明默认 gesture 工具类型为 FINGER。本轮完整打开失败，遇到 Google Sorry/不可访问；没有声称下载了该完整 blob，也没有证明模拟器打包了此精确源码版本。 |
| [support-library-27.1.0 SelectionTracker.java](https://android.googlesource.com/platform/frameworks/support/+/support-library-27.1.0/recyclerview-selection/src/main/java/androidx/widget/recyclerview/selection/SelectionTracker.java) | 已取得并定位 430–433 行：旧默认类型包括 FINGER 和 UNKNOWN。该历史版本与真实 API28 镜像的精确库版本仍分开处理。 |

原驱动使用简化 MotionEvent.obtain，再 setSource(TOUCHSCREEN)。实际结果是 API28 可完成选择，而现代35/36执行过一次物理触屏、仍未从系统文件选择器返回。这与新旧默认工具类型差异相符，是有一手来源支持的解释；尚未据此证明各镜像所带库的精确版本或唯一根因。

后续测试驱动使用完整公共 obtain：一个 PointerProperties（id=0、FINGER）及一个 PointerCoords（观察到的 x/y、pressure=1、size=1），source=TOUCHSCREEN。注入前硬性核对 getPointerCount/getPointerId/getToolType/getSource，并记录文件名 bounds、点位和实际事件属性。仍先稳定观察、最后重新获取节点、只执行一次 down/up，保留 provider 真正退出与固定模型导入断言，不进行盲重试。

临时独立 CI 在该次选择前后保存 `voicekey-provider-before.png` 和 `voicekey-provider-after.png`，用于复核实际画面。它们是全新隔离模拟器的系统文件选择/返回页面，只有固定模型文件名等测试信息；不是模型权重、用户设备截图或自然语音证据。最终是否解决现代选择失败，须以新提交对应的实际 CI、原始报告及后态为准。
