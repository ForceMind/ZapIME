# Android API36 工具面板重启：保留原失败并验证稳定观察

## 固定来源与原始失败

- PR [#122](https://github.com/ForceMind/ZapIME/pull/122) head：`8865c969ef364a671e063e4a853d9e7328ed1815`。
- 原始 [run 44503628454](https://github.com/ForceMind/ZapIME/actions/runs/44503628454)，attempt 1；[API36 job 135495075207](https://github.com/ForceMind/ZapIME/actions/runs/44503628454/job/135495075207)。
- Artifact `14519098401`：`ZapIME-Android-API-36-acceptance`。
- 实际结果保持 **FAILURE**：`tools_empty_editor_restart: Tools panel changed after served restart`。

`original-api36/` 保存八个原始文件的完整字节，不裁剪或重绘截图，不清理原始日志。[清单](original-manifest.json)给出大小和 SHA-256。全部来自受保护的临时 CI 合成测试；不是用户设备或真实输入资料。

## 对照现场之后能够判断的范围

宿主在 uptime `85918` 请求重启，输入法随后记录：

```text
85924:input_restart
85924:restart_keep_tools
87059:input_restart
87059:restart_keep_tools
87077:view_keep_tools
```

同一时段的系统日志明确有 `RESET_NEW_CONFIGURATION`、`showImePostLayout cancelled`。原失败之后保存的 `smoke.png` 与可访问性层级显示“返回键盘”“离线语音”等完整工具按钮；没有记录 `tools_toggle_close` 或 `view_letters`。运行时错误文件为空，本轮没有 ANR。

这些证据支持“测试可能在再次配置重建期间读到不完整节点树”的判断，**不支持单凭失败文字认定产品主动关闭工具面板**。事后完整界面也不能把原失败改判为成功，或证明任何更早时刻都没有短暂产品异常。

旧 `requireToolsPanelStable` 在十次循环中，只要一次查询缺少“返回键盘”就立即按面板变化失败。该查询没有区分可访问性树暂时不可读和真正出现字母布局。

## 本次修改的验收范围

修改仅涉及独立测试 APK 的观察逻辑与主机回归。产品 `ZapInputMethodService`、组字恢复、隐私边界、模型和默认行为不因本修复改变。

新的观察器使用既有物理触摸前观察所采用的公开 IME Insets、动画计数、窗口和节点身份。总预算固定为8秒；缺少节点、几何变化或正在重建只会清空稳定证明，不能算作通过。必须在原宿主、已服务且可见的编辑器上连续观察相同窗口、两个真实工具按钮和稳定几何至少500毫秒，且有至少10个有效样本。

一旦实际观察到可见字母布局或宿主改变，立即失败；持续缺失或持续变化必须超时失败。逻辑没有再次点击“工具”、重开面板、重试输入或改变产品来制造成功。原空白 Editable、隐私选项变化必须退出工具、真实点击返回键盘、后续输入与退出断言全部保留。

纯 Java 回归分别覆盖稳定证明、短暂缺失后重新证明、窗口/节点/几何或动画版本变化、真实字母布局、宿主变化、持续缺失和不可延长的截止时间。它们仅验证观察规则；实际 Android 编译、API28/35/36、设备退出及当前源码 CI 结果必须单独记录。

三星 S25、自然语音和 Issue #38 的其他间歇输入问题不由此测试修复代为验收。
