# API35 组字失败：框架重启线索调查

这是一份只读调查，不宣称复现、修复或确认唯一根因。保留原 run42388891657 attempt1 的失败与后续成功。

## 精确现场

[清单](manifest.json)保存原ZIP、各原成员和安全摘录的字节数/SHA256及原行号；摘录按原字节保存。

| uptime ms | 原始证据 | 可直接确认的事件 |
| --- | --- | --- |
| 61059 | host-commands.txt | 可见种子候选被提交，然后结束其组字 |
| 61452 / 61926 | host-commands.txt | 同一连接1分别完成两次compose，对应已观察的s、sh阶段 |
| 62376 | service-after-finish.txt | 服务有界panel ring保留input_restart |
| 62396 | service-after-finish.txt | 同一ring随后记录view_letters |
| 约62394 | framework-reset.txt | 16:15:50.685的IME进程记录RESET_NEW_CONFIGURATION；约值由原StartInput时间/uptime换算，不作为精确时钟校准 |
| 62494 | host-commands.txt | 连接1收到finish及batch-end |
| 62942 | host-commands.txt | 连接1又收到compose及batch-end |

服务ring来自Instrumentation.finish之后的外部dump，最终已切到Launcher，`restartDecision=new_editor`属于该后续状态，不能冒充失败时决定。host没有记录该时刻新建连接，不代表IME没有重启；ring恰好保存了重启线索。原有系统日志是有界片段，未出现其他消息不等于它们没有发生。

## 源码约束与可证伪假设

官方API35 SDK源码经过[官方元数据](https://dl.google.com/android/repository/repository2-3.xml)的长度/SHA1校验；精确包/文件摘要在清单。其InputMethodService框架配置重置路径沿用当前InputConnection和当前EditorInfo，以restarting=true进入onStartInput，不要求宿主新建连接；show_ime_with_hard_keyboard的设置观察器也调用同一重置路径。官方SDK源码不是本次Google镜像框架二进制的逐字节证明。参考[AOSP公开源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/inputmethodservice/InputMethodService.java)仅作可读入口，不将Android16替代API35源码身份。

在固定8df产品中，retainRestartedComposition要求EditorInfo的初始选区和快照与当前完整preedit一致。若框架复用最初空编辑器的EditorInfo，输入sh后的初始坐标仍可能为0，当前受信选区却在种子+sh尾部，保护会拒绝；随后onStartInput重建解码器，初始化空layout回复经apply结束标记。这条路径可以解释restart→view→finish/batch→下一compose的时序，仍未拿到失败时拒绝原因，不能确证。

直接模型排序回调只发布候选视图，不调用apply或finish；普通view回复返回当时核心快照，活跃组字的layout请求会返回composition_active。单线程worker串行调用核心/按序post回复，不能仅以“异步”推导存在倒序回复。独立的abandonComposition直接finish没有该apply批量路径；原日志单个finish+batch更支持初始化回复假设，但它不能排除与其他框架回调组合的情况。

可证伪预测：如果该假设成立，同连接输入sh后由系统设置通知触发框架重置，应出现INPUT_RESTART、INPUT_REBUILT/空回复finish，而宿主没有新建连接；若新环显示ABANDON_COMPOSITION在先、真正finishInput/隐藏或新的EditorInfo来源，就需要改变归因。不得放宽初始快照/选区保护来匹配假设，不在输入线程增加远端正文读取。

## 最小独立CI实验（尚未执行）

在数字run/attempt、专用临时emulator、精确UID双族断网及原清理门禁下，用公开UiAutomation系统命令反转并恢复show_ime_with_hard_keyboard。只在当前值合法且原样可恢复时执行；创建独立合成Editable，真实touch种子及sh，记录原连接serial与数字选区/span，再触发框架通知。若真实隐藏/结束输入、连接变更或焦点变化，独立记interrupted_not_same_editor而不能当作同编辑器保组字失败。只有持续满足同连接/焦点与显示范围时，再用新鲜几何真实touch i并检查精确正文/组字/候选。

这是下一项实验方案，未运行不算复现。原候选门禁的触摸、候选ID、当前revision、真实Editable与服务退出必须保留，原成功也不将这次失败回改为通过。
