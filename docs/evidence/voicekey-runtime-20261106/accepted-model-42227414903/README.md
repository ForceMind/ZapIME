# 三API双离线语音候选：VoiceKey专项实际通过

固定源码 `be79d084eef1f835e7504f134f46ba315b36df18`，[focused run 42227414903](https://github.com/ForceMind/ZapIME/actions/runs/42227414903)。此轮API28/35/36专用作业全部 **SUCCESS**。产品源码仍与08e849一致；后续测试修复分别加入明确FINGER事件及临时模拟器硬键盘显示策略的准备/恢复，未覆盖产品的系统显示策略。

| API | Job | Artifact | ZIP字节 | 原ZIP SHA256 |
| --- | --- | --- | --- | --- |
| 28 | 127925439223 | 13506458469 | 186055 | `77b7db5044a8916d52f69ae3fe8e1d5506cd35389428699263ddcaf932466ca5` |
| 35 | 127925439326 | 13505819838 | 183984 | `98193e65eae900c642ab91fd756e63b0d97e63173121e3942ce7c82f4367b571` |
| 36 | 127925439181 | 13507016216 | 238014 | `a5b742fc7f3db49c0e892954cd055fbd40088d47e75ad53d421ac7290c083323` |

上述ZIP均已实际下载，逐一重算大小/SHA，与GitHub artifact metadata中的digest、run和源码相符。下载后以本源码的 `verify_reports`、`verify_watchdog_report`、`verify_boundary_report` 严格复核，而非只读取GitHub success标记。每个API的 `download-verification.json` 是额外下载复核记录，不冒充原始artifact成员。

三API实际通过：

- 14个模型主案例：真实系统ACTION_OPEN_DOCUMENT选择及固定包/成员SHA校验；仅VoiceKey、没有X-ASR模型；明确引擎选择；无样本/全零/有效前缀/±1/尾块/跨线程取消；真实JNI非空partial和final、生产确认事务、idle缓存释放及受控实际Android stop watchdog。
- 23项真实Android Editable/生产确认事务，保留最多插入一次、连接/焦点/选区/上下文/权限模拟边界。权限的受控状态检查不冒充操作系统实际撤权试验。
- 实际JNI边界与预算ABI，包括坏预算不消费已有会话。
- 独立绝对时钟producer复用未扩容的生产602帧VoicePcmQueue；每端produced=accepted=125861，即原125490样本及371个零尾样本全部接受；没有用阻塞accept拉慢生产速率或静默丢音。EOF后的排空与final共用30秒预算。
- 激活的真实系统IME和模拟器AudioRecord：预览不自动插入、停止/重录/取消、长空格、光标/焦点/隐藏保护、静音空结果不能插入；切回已核实系统键盘并等待ZapIME服务真正退出。
- 精确产品UID的IPv4/IPv6内核离线拒绝生效，finally只移除本轮规则；模型Download测试文件被删除，没有模型/音频上传。

| API | partial次数 | 首partial ms | producer EOF→final ms | 实际30秒Handler callback ms | 硬键盘显示策略：before→应用读回→恢复 |
| --- | --- | --- | --- | --- | --- |
| 28 | 3 | 1400 | 1625 | 30022 | 1→1→1 |
| 35 | 2 | 1400 | 2173 | 30029 | 1→1→1 |
| 36 | 3 | 1900 | 1588 | 30008 | 0→1→0 |

本轮直接记录了API36初始secure策略为0、准备时读回1、最后恢复到原值；API28/35初始为1。不能据此倒填旧失败运行未采集的secure原值。每端 `acceptance-summary.json` 的policy restored、真实服务退出、共享文档删除，以及 `offline-uid.json` 的两族拒绝与规则清理，均已下载复核。

表中时长仅为单条合成PCM和相应x86_64模拟器的实际观测，起点不包含用户按键/模型校验加载；不代表自然语音、ARM手机延迟、P50/P95或引擎优劣。watchdog使用受控VoiceCapture状态、真实30秒Android主Handler及模型native idle-worker取消/退出，不等于active matrix、AudioRecord或系统IME的超时负向场景；JSON保留该限制。

这轮足以确认本范围内的模型/确认/模拟器IME闭环，不等于三星S25、自然麦克风非空确认、蓝牙/系统真实撤权、手机能耗、正式签名或稳定发布。既有失败原件全部保留在相邻历史目录，不因通过而删除。

本目录保存原报告、JSON、合成UI/文件选择PNG及原job日志；`provenance.json` 固定所有文件摘要，并单独标明额外下载验证。没有模型权重、PCM/WAV、APK、个人用户数据或临时签名下载URL。
