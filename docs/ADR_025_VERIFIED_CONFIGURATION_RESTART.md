# ADR-025 · 通过独立新鲜证明保留配置重启组字

状态：已由 PR #102 合入 main；固定 tree `2c73b5abe92214ad97eb0104f11911fa6a9d3cb6` 的三 API 系统 IME 与 API35 独立配置实验实际通过，见[本轮证据](evidence/android-stability-52f812b-20261108/README.md)。下文早期“随后必须”描述验收要求；真机及任意编辑器 Binder 行为仍不由这些结果推定。

## 已确认问题

API36 run42459746934 的失败前事件环记录 INPUT_RESTART→INPUT_REBUILT→APPLY_FINISH_EMPTY，失败时 decision=selection_not_owned；同一个独立宿主连接的 shi 从组字变成提交正文，下一键只在尾部建立新组字。原 framework 日志记录 RESET_NEW_CONFIGURATION。API35/另一 API36 的历史现场提供交叉线索，具体触发配置及所有历史间歇问题的根因不能统一推定。

框架配置重置可以复用当前 InputConnection 与旧 EditorInfo，以 restarting=true 重入 onStartInput；旧 initialSel/初始文本不能当作当前 App 快照。直接放宽原条件有覆盖外部编辑风险，继续重建核心则会用空初始化回复结束当前标记。

## 保留普通重启保护，收窄新鲜路径

普通重启仍优先执行原 EditorInfo 初始快照、完整组字、同编辑器与原精确折叠选区校验。新路径仅限 API31+，并且原强校验因为 selection_not_owned / initial_context_mismatch 拒绝；当前 EditorInfo **同一对象引用**、InputConnection **同一引用**、原编辑器元数据/隐私选项相同，正常中文 letters、非搜索/非私密/非禁止学习、已有核心就绪，且 SelectionEchoTracker 仍以零 slack 精确拥有完整组字区间。未知尾部快照、标记交接或已有未排空回复不走新路径。

用独立线程调用公开 getSurroundingText，最多读取256个前方和32个后方UTF16单位；不在主线程、输入/核心线程、语音或资料线程读取。返回必须有规范非负 offset、精确绝对折叠选区、完整 preedit 后缀、此前可信有限 prefix（可为空）以及最初允许快照中保留的32单位后方内容。异常、缺失、超限、文本/位置/后方变化均拒绝。一个原子返回中的文本与 selection/offset 共同构成新证明；不把数值相似的旧 EditorInfo 当新快照。

公开 takeSnapshot 的平台契约明确 IME 调用返回 null，故没有以其构造假能力。COMPOSING NoCopySpan 不能可靠跨 Binder，CursorAnchorInfo 又没有本请求 token，均不作新鲜证明。原 owned 范围和 pending 选区观察继续约束标记；这里不承诺任意恶意编辑器的跨进程原子提交。

## Pending、总顺序与取消

公开 windowShown/windowHidden 生命周期状态在建任务和完成时均核对，隐藏后不能新读后台编辑器；此状态独立于会在onStartInput内清掉的rankingVisible。

进程共享物理 IPC 单槽；忙时拒绝，不排队。800ms 是逻辑有效期，超时/销毁不会假称已经杀死阻塞 IPC；其实际返回前不接受另一物理读。实际IPC线程开始前还核原子read permit及单调时钟deadline，先取消/先过期的任务不发读取；已经进入读取的不虚构可强停。请求仅在内存保存有限内容，不持久化或记录正文。

Pending 不重建输入核心、不发送空 layout、不写编辑器。完整用户按键/按钮/切布局/语音/搜索等入口以及 send 捕获的新动作进入有界队列；pending 前 engine 已发出的回复和直接 insert/edit 回调进入旧回复队列。总计最多64项/64Ki UTF16预算，溢出明确失败。核心队列只追加一个 post 回调屏障，确保其前所有旧回复已进入 hold；proof 和 barrier 无论谁先到，均必须同时完成，先恢复标记、再释放旧回复、最后按序释放新动作。候选请求保留所见 ID/revision。

只允许已经预测的迟到 selection echo 或仍精确覆盖原 owned 区间的回报更新本握手的版本；外部移动、范围/标记不一致立刻失效。语音的独立 selectionVersion 检查不变。恢复前再次核 epoch、连接/EditorInfo 引用、全部元数据、隐私、preedit、owned范围和版本。

先消费一次性 ticket，再进入 restore-in-progress 闸门调用 setComposingRegion；只恢复标记，不写正文。setter/batch 可能同步重入 selection/restart/finish，返回后再次核身份/版本；失效时不能刷新 tracker、释放旧回复或新动作。每一步 drain 也核原 epoch/连接。拒绝、超时或隐藏时丢弃迟到请求和临时动作，使用原地 finish/decoder reset 保留 App 正文，不写空 setComposingText，也不应用空初始化回复；明确提示重新输入。尚pending、未consume且非restoring的同一ticket在原scope/owned范围/selectionVersion/隐私/可见状态和原deadline全部不变时，重复同编辑器start复用原ticket/barrier/队列，但必须提升generation并失效旧proof。即使旧read已返回而callback未消费，或旧proof已收到而barrier未到，也不能以旧generation ready/consume。旧物理read实际返回后最多串行补读一次；每ticket至多2次submission/物理read，worker开始前用volatile generation与原子permit跳过已过时读取，不延预算。补读已提交后再次通知则明确取消；不满足条件或已经restore/consume的再次重启仍明确退役并提示重输，不能静默丢队列后称恢复成功；新编辑器/finish/销毁只取消旧 ticket，不把旧状态写进新编辑器。恢复中重入的新用户动作仍有界保留；屏障后又出现未知旧reply则明确退役。每次onStartInput依旧重开学习session、取消语音/触摸并记录固定事件。

候选上下文仍沿用现有可信来源；配置回调期间只临时持有之前允许的有界链，finally清除临时引用。proof 确认同一前缀后可恢复该链；密码、禁止学习和搜索不捕获此材料。没有从普通 App 正文补建长期历史。

## 验证

纯 JVM 回归覆盖：offset/绝对选区/preedit/prefix/after/长度/UTF16拒绝、proof/barrier两种先后次序、旧直接插入与旧reply优先、consume重入、总容量、cancel/超时后迟到拒绝、进程物理槽直到实际 IPC 返回、迟到 own echo 与外部移动。原 SelectionEchoTracker 和诊断环回归保留。

旧回复的单次apply内部也在原有beginBatch、commit/compose/finish、删除读取/写入后复核epoch、inputStarts与连接，避免同步重启后继续执行本回复余下写入。新增独立Android Editable方法探针核commit/compose/onFinishInput、beginBatch及delete getter重入，使用未附着的合成Service实例，不调用活动系统IME；其四项独立报告不能冒充框架重启/远程Binder证明。

随后必须实际回读同源码 Android API28/35/36 及独立配置重启实验。系统实验若真实隐藏/结束输入或变连接，应分类为中断，不能当作同编辑器保护失败或通过。真实触摸、候选身份、独立 Editable、原选择/隐私/服务退出断言不放宽。模拟器和源码/编译仍不能替代 ARM/S25 验收。

## 普通强保留路径的重入收口

普通EditorInfo初始快照/完整owned选区准入保持原样；通过准入后也必须在batch begin、setComposingRegion及end返回之间复核调用frame与模式/隐私/原preedit/owned范围。局部固定坐标不能在setter回调结束输入或切换编辑器后继续应用。原连接的endBatchEdit只为平衡已完成begin，不授权在新frame写元数据。

私有三态区分REJECTED（正常拒绝，可继续既有fresh/fallback）、RETAINED和INVALIDATED（原调用失效，外层立即退出，不能用旧info重建）。inputLifecycle只在start/finish入口递增，识别自身abandon提升epoch期间发生的nested生命周期；不记录内容、不改协议。合法旧reply可在drain期间推进preedit，所以排空后只检查frame/模式/隐私，不要求旧preedit不变。

[旧实际方法复现与新回归](evidence/ordinary-restart-reentry-20261108/README.md)包含动态提取实际Java方法的合成生命周期6场景；原drain计数只证明被调用，不声称旧reply实际上屏或RemoteInputConnection接受失效setter。新增Android Editable探针只执行区域恢复helper的begin/region/end重入，未覆盖整个框架绑定的Service路径或Binder。新固定源码的真实IME/框架重启和设备验收仍须单独执行。
