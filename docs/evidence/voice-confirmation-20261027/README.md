# 非空语音确认事务验收

最终测试源`7a8667a746c5d500bd4984407300ba27e7ddefc8`，PR #60 / Issue #59，代码合并`7df6f76dc217b690193f29321116137039b6282c`。生产事务固定一次非空识别结果，由主线程在完成时核对连接、ticket、epoch、选区版本、绝对折叠光标、隐私/搜索/组字与有界上下文；先消费并清理请求，最多尝试一次commitText，finally恢复键盘，不在复核后/提交前恢复UI引入重入。不自动重试拒绝或异常，也不把API返回值当最终跨进程正文收据。

## 实际CI与原始产物

| 工作流 | 精确run | 结果 |
| --- | --- | --- |
| Android原生输入 | [40517361507](https://github.com/ForceMind/ZapIME/actions/runs/40517361507) | completed/success；双架构APK，API28 job122234795761、API35 job122239280154、API36 job122239280103 |
| 通用开发检查 | [40517361436](https://github.com/ForceMind/ZapIME/actions/runs/40517361436) | success；三系统共享核心及Apple原生基线 |
| 离线语音CPU | [40517361250](https://github.com/ForceMind/ZapIME/actions/runs/40517361250) | success |
| 独立密文服务 | [40517361276](https://github.com/ForceMind/ZapIME/actions/runs/40517361276) | success |
| Windows既有基线 | [40517361393](https://github.com/ForceMind/ZapIME/actions/runs/40517361393) | success，不代表#41已解决 |

每API原生报告均实际20类通过，原中文片段、六类空PCM、模型/Keystore/录音权限/取消/60秒与两轮独立App输入、静音麦克风UI、系统IME切换退出也通过。UI成功stream均为`empty_result_insert_checked=true`。`api-28/35/36/`各保存12个从对应ZIP逐字节选出的原文件，含`voice-commit-report.json`、native/empty报告、native/UI instrumentation、合并门禁、系统IME及退出、crash/活动错误、宿主trace和停止截图；三个`api*-success.log`为原作业精确选段，不冒充全部日志。

| 完整下载并核验的ZIP | 字节数 | SHA256 |
| --- | --- | --- |
| API28 development 12740756313 | 233,591,377 | `8e96e0866617b85199a82698e764bb75a17aefadb8828c1c895ef62793ab689d` |
| API35 12742010264 | 2,436,968 | `dd2c411158471931191abb738a2475a66862aee5b541c23c2a732e3bf4fd2f83` |
| API36 12742465377 | 2,437,862 | `1d0fa8d94e9d8c00c671c5f612143ac72a028b3de1f1d4962d082c7a2438e73b` |

`package-verification.json`记录实际APK/模型SHA、固定六成员核对、GPL/语音许可、权重/PCM未内置及产品DEX三个新事务类型；测试探针descriptor未进入产品DEX。APK大小仍为50,958,904B，不能据大小推断代码未变：实际DEX154216B/SHA5b2be19f60c767372b4fd21b24b6491b16359fb46d044fa507f21f2e8ddd33f3，旧59c9e8e的DEX151100B且没有新事务类型。只核验本地字节与CI签名/对齐检查，不声称本地重复签名验收或ARM运行。固定包见[试用指南](../../ANDROID_TRYOUT.md)。二进制、模型和PCM不进Git。

`ci-summary.json`保留精确源结果，不预记后续main/文档head的独立运行。读取时run为completed/success，三job的`Complete job`步骤均completed/success，但jobs集合仍把API28顶层返回为in_progress/null；这些原返回值保留，未手改成completed。执行结论另有原作业success步骤、报告及完整run结果支持。`manifest.json`记录本目录除自身外的大小/SHA及原件/派生来源。

## 已有本地结果

`local-java-compile.json`保存实际JDK17.0.20与固定官方API36 android.jar（SHA256 d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a）的两组source8/target8编译：产品25源/45class，target instrumentation 3源/8class；返回码均0，源码SHA在编译前后相同。这里只是Java编译，不称APK、JNI或模拟器执行。现有12个Android工具/设备隐私/模型边界Python测试实际通过，采集器语法与git diff --check通过。

## 已执行的合成确认门禁

新增20类同UID合成探针：精确一次提交、提交中重入/取消、取消后迟到完成、连接/ticket/epoch/选区版本变化、重复正文中上下文相同但光标变化、选区/未知位置、策略排除、前后正文改变、上下文null/超长/异常、编辑器拒绝/异常、错误线程拒绝。探针从原JNI识别成功的局部变量直接使用真实合成解码文字，实际Android EditText/Editable核对精确正文与尝试次数；不在日志/报告写识别或上下文正文。

正向用例还故意在界面恢复回调把实际Editable光标移到0，仍要求识别文字先插入原位置；若未来把恢复提前到提交前，真实正文比较就会失败。它验证恢复与提交的次序，不只是复刻布尔谓词。此补充后target3源/8class已重新编译，记录中的全部产品/测试SHA与当前源码一致。

`voice-commit-report.json`必须包含全部20个固定ID、JNI来源标记、实际Editable通过项，缺项即拒绝；本次三API均实际齐全且全部通过。每个报告为3项COMMITTED、14项INVALID、2项REJECTED、1项ALREADY_FINISHED，真实commit尝试合计5次；worker_thread_rejected先拒绝worker调用，再在主线程有效提交一次，故该行最终为COMMITTED。所有终态再次完成均不能重试，取消清理的事务不留正文/上下文引用。原六类空PCM、原中文片段/加载计时、模型/权限/Keystore/取消、60秒与三API独立系统IME两轮输入/麦克风UI/真实服务退出均保留并通过；这里是7a8667a新结果，不借先前59c9e8e的五绿。

这是生产事务与有界读取方法的合成验证，未激活探针中的Service；没有反射框架私有字段、没有改真实系统IME，也没有把模型结果注入产品接口。它不能冒充物理确认键、真人麦克风、ARM/三星或自然语音质量；这些与#53仍开放。
