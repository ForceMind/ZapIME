# Android 同编辑器重启与组字

`InputMethodManager.restartInput` 重建同一编辑器连接时，旧宿主无条件丢弃 Rust 会话。PR #39 先提交独立编辑器回归，再增加有条件保留；目前实际模拟器结果以 STATUS/CI 为准。

只有 API30+、restarting=true、已就绪中文非隐私会话、非搜索关闭过程、组字长度 1–128、package/fieldId/inputType/imeOptions 一致，且选区完整折叠在本次组字尾时，才检查 EditorInfo 随连接带来的初始前文。精确匹配当前组字后，恢复 composing region 并保留引擎/代次和按顺序等待的结果。任何检查不满足则走既有全量重建；不凭相同光标位置猜测正文。

选区回声跟踪来自固定 GPL 上游，成功写入后记录预期，迟到回声不会把较新的预测退回旧光标；无法预测的删除/编辑事件显式失效化。跟踪只记录 UTF-16 位置，不保留正文。编辑器快照不支持、隐私、切换字段或非 restarting 的生命周期仍不保留。API28 保持原重建行为。

恢复检查不会在 UI/输入线程调用远端 getTextBeforeCursor/getTextAfterCursor，避免不响应的宿主阻塞按键。API 文档：
- https://developer.android.com/reference/android/view/inputmethod/EditorInfo#getInitialTextBeforeCursor(int,int)
- https://developer.android.com/reference/android/inputmethodservice/InputMethodService#onStartInput(android.view.inputmethod.EditorInfo,boolean)

回归从测试应用实际 Editable 观察：触屏输入 ni → 真实 restartInput 和新连接 → IME 窗口仍为 ni → 继续 hao → 你好，全文严格相等。反例由应用把 ni 替换为同长 xx、保留同样组字范围，然后重启；必须拒绝沿用旧 ni，续输 hao 只能得到 xx好。没有向 IME 注入假原生结果，也不跳过隐私/九键/语音/两轮及实际退出门禁。JVM 保留上游回声、迟到回报、重启边界与删除不确定量测试。

这一确定性缺口不证明 #38 所有历史 API36 间歇 UI/组字失败有同一根因。模拟器通过也不等于 ARM/三星/目标 SDK 36 应用、自然语音或真实交互全覆盖。
