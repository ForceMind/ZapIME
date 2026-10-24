# Android 语音入口触摸时序：失败证据与测试驱动修正

2026-10-24，源码`8a612c888ca9e288c0ce7673a6f4d406abda1074`的Android工作流`40106347773`中，API28成功；API35/36的两轮主键盘输入门禁成功，随后独立语音UI门禁在`voice recording preview`阶段因找不到“离线语音”按钮失败。整条工作流仍为失败，不能用已通过的输入、JNI或方法测试抵消。

本报告保存原始失败，说明新的测试驱动修正。**具体那两次触摸是否使用了移动前坐标仍属推断**；新日志与公开框架源码提供了可验证的时序依据，以下前半部分记录修正提交时的事实；后续实际验收见文末。

## 实际下载与原始时序

[证据目录](evidence/android-voice-touch-40106347773/README.md)保存两份实际下载产物的关键原始文件及逐文件manifest。

| API | 作业 | Artifact | ZIP字节数 | ZIP SHA256 |
| --- | --- | --- | --- | --- |
| 35 | `120910589337` | `12565316028` | 2,483,902 | `497c47da0dfb765f7be5d458c9bef7304badda71bf42e1fbc42b7fb5717d2620` |
| 36 | `120910589323` | `12565346772` | 2,474,821 | `30938b724abde572f9c0c717c54d555be39b65484c74571383f746fc3e08689f` |

下载ZIP的大小、SHA与GitHub元数据均已实际比对。截图未重绘、裁剪或缩放；全部内容来自专用模拟器和合成测试应用。

新增加的单调uptime与固定事件码给出如下时序：

| API | 第一次工具触摸 | 产品实际事件 | 慢IPC取消后的第二次工具触摸 |
| --- | --- | --- | --- |
| 35 | `266044` attempt → `266073` sent | `266074:tools_open` | `267008` attempt → `267041` sent，随后没有第二次工具动作 |
| 36 | `231408` attempt → `231438` sent | `231439:tools_open` | `231948` attempt → `231971` sent，随后没有第二次工具动作 |

两个服务的面板事件队列都仅有5条，未满16条上限，没有`tools_toggle_close`，也没有第二次触摸之后的input start或view recreate。因此现有证据不支持“再次点击把已打开工具面板关闭”的解释。最后的截图和层级均显示普通字母键盘，`voice guard test`合成正文保持完整；`cancelVoice(true)`恢复letters的实现与现场一致。未记录ANR或产品fatal。

原驱动把`injectInputEvent`的返回值当成触摸步骤完成，然后直接查找下一个控件。这里的“sent”只确认注入返回成功，不能证明工具按钮的点击动作已经执行。

## 公开框架行为与尚待验证的推断

固定AOSP Android15/16源码显示：公开的两参数`UiAutomation.injectInputEvent(event, sync)`默认等待窗口动画；连接实现对`ACTION_DOWN`先执行`WindowManager.syncInputTransactions(waitForAnimations)`，再将已有坐标的事件交给InputManager。`ACTION_UP`后还会同步一次。

旧helper在调用前已读取`getBoundsInScreen`。取消语音后，语音body为234dp，字母body为208dp；布局可能移动26dp。如果先取坐标、随后在注入内部等待窗口动画，事件可能投到移动后的其它位置。这个机制与“注入成功但没有工具按钮动作”的现场相容。

原始驱动没有记录那两次具体bounds，不能把这段推断升级为直接观测的坐标错位。其它布局或节点时序仍可能参与。新候选记录实际几何与动画观察值，用于后续区分。

## 本次仅修改测试宿主和驱动

`ProbeActivity`在API30+的独立嵌套类中注册公开`WindowInsetsAnimation.Callback`，仅跟踪IME的`onPrepare/onEnd`。活动计数和变化版本以volatile字段供测试读取，记录有界固定事件；`onProgress`原样返回Insets，不改变系统动画。API28不加载新API类，继续通过实际IME几何检查。

语音入口使用新的工具面板准备步骤：

1. 在测试App已释放、可响应时执行公共`waitForIdle`；重新取得IME窗口与目标Button。
2. 连续至少300ms检查宿主焦点、已观察到的IME动画计数为0、窗口/按钮bounds和动画版本稳定；最长等待8秒。只记录固定事件码、数值bounds、计数与uptime，不记录输入正文。
3. 稳定后重新取得bounds，注入一次物理DOWN，真实等待50ms，再用当前时间注入UP。注入拒绝时失败，不盲目重复切换按钮。
4. 必须观察到同一IME窗口里的“返回键盘”和“离线语音”，并再次通过稳定检查。若已存在合法工具面板，明确记录并保持，不再次toggle。

普通`startDictation`随后以同样的稳定几何完成“离线语音”的实际触摸，仍等待真实录音状态。没有用隐藏的`syncInputTransactions`/三参数测试API，没有关闭全局动画，也没有用直接`ACTION_CLICK`替代普通语音入口触摸。

故意暂停App IPC的测试仍只在那一段使用原有具名“离线语音”和“取消”无障碍动作。新准备步骤在设置暂停fixture之前；取消计时开始、动作、`<1800ms`门槛及finally释放均保留。稳定等待放在计时外，后续等待发生在App已释放之后，不稀释取消响应指标。

产品Service、JNI、录音、权限、模型、输入核心和普通键盘测试逻辑均未修改。原有全部正文、隐私、焦点、取消、重录、隐藏及真实服务退出断言继续执行。新候选不宣称修复#38的全部间歇问题。

## 已执行与待执行

已实际完成：两份原始ZIP大小/SHA及24份选定原文件逐字节保存；JDK17使用`-source 8 -target 8`和API36的`android.jar`编译完整test host Java，退出0，仅有既有API/source8常规警告；源码差异检查通过。

在修正提交时，仍需新CI在API28/35/36运行完整输入及独立语音门禁，并检查新动画/bounds轨迹与原始截图；后续完成结果如下。上述本地编译不等于APK打包、模拟器、自然语音或真机通过。旧失败保留，不能仅重跑旧失败挑选一次成功。

一手源码：

- [Android16 UiAutomation默认注入行为](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/app/UiAutomation.java)
- [Android16注入前后窗口同步](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/app/UiAutomationConnection.java)
- [Android15同一连接实现](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/app/UiAutomationConnection.java)

## 后续实际验收（2026-10-25）

最终源`ca0dc35c1fcfc2bb66573a5882f1b178f4c6744b`的[Android40108971495](https://github.com/ForceMind/ZapIME/actions/runs/40108971495)已实际完成success，API28/35/36均通过两轮主键盘、独立语音UI及真实服务退出。另四工作流也已success，#48合并`347a995`；研究源a2a178b/d4abc5e纳入相同测试驱动后各五工作流亦success。

实际核验的[API36成功证据](evidence/android-voice-touch-success-40108971495/README.md)保留14份原文件、3张原图、精确成功摘录和作业元数据。保留trace中的动画版本、稳定几何、9组物理触摸返回和4次工具双控件后置条件支持新驱动真实执行；独立语音UI、两主轮及真正切回系统IME退出均通过。成功包无产品panelEvents快照、早期driver部分已环淘汰，故不声称完整动作时序或旧失败唯一原因；模拟器静音录音和合成PCM不代表自然语音、ARM/三星真机、触感或英语翻译完成。
