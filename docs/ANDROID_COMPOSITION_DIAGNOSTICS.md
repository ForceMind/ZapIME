# Android 间歇组字：固定事件诊断

此切片为定位历史 API35 失败补充证据，不宣称修复了产品组字问题。固定原失败在[空上下文保护验收](evidence/context-guard-20261107/README.md)；后续同源码成功仍单独保留。

## 已知现场与尚缺信息

run `42388891657` attempt 1 的 API35 系统 IME 门禁在第八次拼音触摸失败。独立宿主已接受可见候选“你好”，随后在同一连接 1 上记录 `compose`、`compose`、`finish`、`compose`，报 `key_committed_unexpected_text`。没有新连接或窗口失焦记录，也没有产品 fatal/ANR。原记录没有服务的失败时组字原因，不能据此区分下表的几条路径；它也没有证明触发者一定是模型、选区回报、重启或测试宿主。

## 新增证据

Service 的 `dumpsys` 增加 `ZapIME composition trace` 行，只包含最多 64 个固定枚举事件、递增序号、uptime 毫秒及本进程 epoch。环中没有输入、候选、上下文、键名、编辑器身份、账号或秘密；不写文件、不联网。记录位置沿用现有分支，不改变线程、100ms 标记交接预算、候选排序、正文写入或取消条件。

| 事件 | 对应路径 |
| --- | --- |
| `INPUT_NEW / INPUT_RESTART / INPUT_RETAINED / INPUT_REBUILT` | 系统进入输入会话及原重启判断 |
| `INPUT_FINISHED` | 系统 `onFinishInput`，可与其他 finish 路径区分 |
| `SELECTION_ECHO / SELECTION_EXTERNAL` | 原 SelectionEchoTracker 实际判断 |
| `MARKER_WAIT / MARKER_APPLIED / MARKER_SETTLED_BEFORE_INPUT` | 有界标记交接及下次操作前结算；`APPLIED` 不等于证明计时器到期 |
| `ABANDON_COMPOSITION` | 原有外部范围变化、队列溢出等导致的原地结束与解码器重置 |
| `REPLY_HELD / REPLY_OVERFLOW / REPLY_STALE / PERSONAL_REPLY_STALE / REPLY_APPLY` | worker 回复的已有排序/epoch 边界；`send` 的输入/独立搜索回复和个人资料回调在上层 epoch 拒绝处分别记录，不等到 `apply` 才观察 |
| `APPLY_PREEDIT / APPLY_COMMIT / APPLY_CLEAR_OWNED` | 原回复要求写组字、提交或清除自己拥有的组字 |
| `APPLY_FINISH_EMPTY / APPLY_FINISH_AFTER_COMMIT` | 原 `apply` 的空 preedit 分支结束标记，可区分先前是否提交文字 |
| `APPLY_ERROR / APPLY_NO_CONNECTION` | 未写正文的错误或缺连接退出 |

`CandidateImeProbe` 的初始、已排序、结束和失败服务快照纳入新行。失败前在 `Instrumentation.finish` 之前采集，原始错误继续抛出。它仍使用公开窗口与新鲜几何进行单次真实触摸，并坚持候选身份、当前 revision、独立 Editable、无 composing 与实际服务退出断言；没有增加 A11y 点击回退、重试触摸或延长门禁。

独立 test-only 宿主的有界日志再记录真实按键序号的尝试/观察、每次 InputConnection 命令完成后的正文长度、选区和 composing 数字范围。`phase=attempt` 在等待稳定按钮及注入之前记录，只表示一次尝试开始，不表示事件已注入或获得 ACK；`phase=editable_observed` 只在原有精确正文/组字断言满足后记录。不记录正文或触摸键名。它不是产品用户日志；生产输入法没有新增远端正文读取。

epoch 观察覆盖 `send` 的成功核心回复（包括独立搜索结果）、个人资料 query/edit/commit 的回调，以及已有 held reply 排空；读取的仅是整数 epoch 和固定路径枚举，原上层准入表达式不变。个人资料因隐私、页面身份、面板或 query serial 失效而拒绝、worker 抛异常、初始化/重置、模型排序和语音回调各有原保护，但本切片没有将它们冒充已被 `REPLY_STALE` 全面计数。

## 验证与接续

`bash scripts/test_android_selection.sh` 同时运行既有 SelectionEchoTracker 和新事件环 JVM 回归，检查事件顺序、容量、快照不可变性、不同 finish 原因，以及接口不接受任意文本。完整 Android 门禁需在本修订另行运行；编译或 JVM 测试不能代替系统 IME 复现。新失败应对照服务固定事件与独立宿主命令，先确定 finish 分支，再设计对应确定性回归。没有新的失败证据时，继续保留历史问题未决。
