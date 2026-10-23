# Android 同编辑器重启与组字

`InputMethodManager.restartInput` 重建同一编辑器连接时，旧宿主无条件丢弃 Rust 会话。PR #39 先提交独立编辑器回归，再增加有条件保留；目前实际模拟器结果以 STATUS/CI 为准。

只有 API30+、restarting=true、已就绪中文非隐私会话、非搜索关闭过程、组字长度 1–128、package/fieldId/inputType/imeOptions 一致，且选区完整折叠在本次组字尾时，才检查 EditorInfo 随连接带来的初始前文。精确匹配当前组字后，恢复 composing region 并保留引擎/代次和按顺序等待的结果。任何检查不满足则走既有全量重建；不凭相同光标位置猜测正文。

选区回声跟踪来自固定 GPL 上游，成功写入后记录预期，迟到回声不会把较新的预测退回旧光标；无法预测的删除/编辑事件显式失效化。跟踪只记录 UTF-16 位置，不保留正文。编辑器快照不支持、隐私、切换字段或非 restarting 的生命周期仍不保留。API28 保持原重建行为。

恢复检查不会在 UI/输入线程调用远端 getTextBeforeCursor/getTextAfterCursor，避免不响应的宿主阻塞按键。API 文档：
- https://developer.android.com/reference/android/view/inputmethod/EditorInfo#getInitialTextBeforeCursor(int,int)
- https://developer.android.com/reference/android/inputmethodservice/InputMethodService#onStartInput(android.view.inputmethod.EditorInfo,boolean)

回归从测试应用实际 Editable 观察：触屏输入 ni → 真实 restartInput 和新连接 → IME 窗口仍为 ni → 继续 hao → 你好，全文严格相等。反例由应用把 ni 替换为同长 xx、保留同样组字范围，然后重启；必须拒绝沿用旧 ni，续输 hao 只能得到 xx好。没有向 IME 注入假原生结果，也不跳过隐私/九键/语音/两轮及实际退出门禁。JVM 保留上游回声、迟到回报、重启边界与删除不确定量测试。

这一确定性缺口不证明 #38 所有历史 API36 间歇 UI/组字失败有同一根因。模拟器通过也不等于 ARM/三星/目标 SDK 36 应用、自然语音或真实交互全覆盖。

## 标记丢失与重启回报的先后顺序

首轮5937311在真实API35仍未保留ni，API36在显式重启前已丢失ni。失败ZIP/原文已保存，不能声称该实现已通过。复核固定上游发现漏迁移了相邻的标记丢失衔接：当应用先报告组字范围为-1但光标仍精确在已知完整组字尾时，上游暂缓100ms；随后restartInput会取消该待处理回报，才能利用原范围验证新连接。

现在同样只暂缓精确已知范围；新按键前先结算未被重启接手的回报。若超时、光标移动或其它外部变化已不再拥有完整组字，则原地结束标记并异步reset解码器并提升epoch，保留应用正文及新选区，不使用空setComposingText去覆盖它。新连接仍须身份/初始快照精确匹配，不增加远端同步上下文读取。增加应用去掉标记但不重启的真实编辑器反例：保留ni正文，后续hao只能产生ni好。

服务dump只增加计数、固定判定原因码与是否有待交接回报，不保存/打印正文、前后文、包名或密钥。测试宿主只记录系统初始前文长度，用于确认真实EditorInfo协议。这个诊断不生成成功结果，也不代替完整门禁；新候选尚待CI，尚未排除所有快速输入/间歇启动问题。

额外回归覆盖组字时把光标移到开头：保留旧ni、原地结束标记，后续hao选好必须为好ni，而非覆盖ni或写到旧位置。

ZapIME引擎结果异步返回，所以标记交接期间的结果先按原序暂存（最多64条、合计65536个UTF-16序列化单元），验证新连接后再按序应用；未交接而失去范围时随epoch丢弃，不能让后来的结果越过早到结果，也不在未核对的范围写入。超限明确提示重输并安全重置，不自动覆盖应用内容。只是短时内存交接，不是持久队列；真实快速输入/现代API仍待CI及设备验收。

## 最新连接的真实操作观察

73a6722的API28/36完整通过，API35仍失败。App创建IC和全局idle可能早于IME收到start；现在独立wrapper记录真实操作后的连接序号，等待最新IC实际收到组字/结束/提交再验证ni及续输，不改变产品或假造结果。交接期间任意快输入仍是#38独立未完成风险，不能以等待正常交接后的通过替代它。

最新观察在API28暴露了“创建即served”的错误假定。改为观察编号晚于重启前的实际连接命令；产品同时按标准beginBatchEdit/endBatchEdit封装一轮commit/compose/finish和恢复region，避免中间态回调，观察器只记录真实batch-end。空初始化不再依赖finish这类可能无实际变化的操作必须送达。新产品改动在环境断连期间尚无本地编译/设备结果，仍待CI；所有原正文断言保留。
