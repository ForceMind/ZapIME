# API36 稳定几何触摸后的真实成功证据

源为PR #48代码`ca0dc35c1fcfc2bb66573a5882f1b178f4c6744b`，Android run `40108971495` attempt 1，API36作业`120918560872`。GitHub元数据中API28/35/36三个作业均success；本目录实际下载并检查的是唯一API36 acceptance产物。

Artifact `12566670697`，`ZapIME-Android-API-36-acceptance`，2,436,255字节；下载ZIP的SHA256与GitHub公布值一致：`66902516879645e28d1880625a69691acd78e831f142a58ff629f7570ae0c09b`。没有下载约两百MB的产品产物、模型或其它API的ZIP。原文件及选段的逐文件字节/SHA在`manifest.json`中。

## 已实际核验

- `native-keyboard-round-1.log`、`native-keyboard-round-2.log`：两轮完整原生键盘门禁PASS；原有正文、组字重启、隐私、资料及退出要求保留。
- `ui-instrumentation.txt`：独立语音UI PASS，包括暂停编辑器IPC时取消、实际模拟器麦克风预览/停止/重录、长空格入口、光标/焦点/隐藏取消和不自动写入合成正文。
- `combined-gates.json`：主键盘和独立语音两个脚本退出码均为0。
- `instrumentation.txt`、`native-report.json`：独立JNI合成中文PCM解码与AudioRecord生命周期通过。模拟器录音为静音，不能称自然语音或真机准确率通过。
- `ime-teardown.txt`、`system-keyboard.json`：实际切换到已验证的预装系统IME后，服务列表没有ZapIME输入服务；不是仅在内存中修改开关。`crash.txt`与`native-runtime-errors.log`保持原始0字节。

`voice-recording.png`、`voice-stopped.png`分别是聆听和静音停止后的原图；`native-tools-restart.png`是第二轮同编辑器重启工具面板验收的原图，与产物当前`device-smoke/tools.png`逐字节相同。三图均为320×640，没有重绘、裁剪或缩放，画面中的文字均为合成测试内容。

## 新时序与证据边界

`host-lifecycle.txt`保存公开IME动画回调和最后64条有界driver事件。保留段中核验了9组完整物理DOWN及返回结果，均为`down=true up=true`，从DOWN记录到注入返回为70–87ms；该间隔包含调用开销，不是精确UP事件时间。另有4次工具面板双控件后置条件完成记录。具体配对见`retained-sequence-check.json`。

例如，在隐藏后重新显示键盘的保留片段中，同一uptime时钟记录：

```text
317362:ime_animation_prepare active=1
317573:ime_animation_end active=0
317914:voice_tools_geometry w=66 window=[0,358][320,640] button=[2,380][54,426] animations=0 version=6 focused=true panel=true
318247:voice_tools_geometry_stable w=66 window=[0,358][320,640] button=[2,380][54,426]
318283:voice_tools_physical_down w=66 window=[0,358][320,640] button=[2,380][54,426]
318361:voice_tools_physical_result down=true up=true
318856:voice_tools_open_postcondition_verified
```

这些是从原始host/driver分组日志选出的实际行，用相同单调时间关联。几何稳定窗口、一次物理触摸及可见工具面板后置条件有真实记录，不再仅凭注入ACK判断动作完成。

成功路径没有调用失败专用`dumpCiState`，该ZIP没有产品`panelEvents/tools_open`服务快照；64条driver环也已淘汰最初慢IPC阶段附近的部分事件。因此本目录不补造产品动作时间戳、不宣称保存了完整逐事件轨迹。工具动作的关联依据是实际双控件后置条件和最终完整UI PASS。

`workflow-job-success-lines.log`是该API36原始作业日志中的精确成功行选段，`workflow-jobs.json`是三个API作业的元数据快照。旧失败仍见[原始触摸失败](../android-voice-touch-40106347773/README.md)和[首次启动工具面板失败](../android-tools-launch-40103726412/README.md)。本次成功不能倒推旧坐标失配为唯一根因，也不表示所有#38间歇问题、自然语音、ARM/三星真机或英语翻译功能完成。
