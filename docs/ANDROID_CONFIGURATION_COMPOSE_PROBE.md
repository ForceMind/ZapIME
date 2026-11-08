# API35 框架配置重启与组字实验

本实验从已保存的组字诊断切片接续，只改独立 test-only 宿主、严格报告器和 CI，不修改产品。历史失败线索是同一宿主连接的 sh 组字期间出现框架 `RESET_NEW_CONFIGURATION` 与服务 `input_restart`，随后 finish/batch；尚未确定失败时重启拒绝原因。本实验未运行前不能称已复现或修复。

## 实际触发路径

新增 `scripts/android_configuration_compose_smoke.py` 复用原系统 IME wrapper 的数字 run/attempt、专用 emulator、API35 校验、产品精确 UID 双族断网、清合成资料、预装系统键盘恢复、实际服务退出和 fatal/ANR 门禁。输出进入独立 `target/candidate-configuration-android`，不覆盖原候选专项。`candidate-ranking.yml` 增加独立 API35 Google 镜像 job；原方法门禁与原六次上下文候选按键路径保留。

独立宿主使用真实触摸输入 nihao、点击当时可见的双字候选作为种子，再输入 sh。只在系统设置 `show_ime_with_hard_keyboard` 当前值为规范 0/1 时，以公开 `UiAutomation.adoptShellPermissionIdentity(WRITE_SECURE_SETTINGS)` 临时取得测试进程的设置写能力，调用公开 `Settings.Secure.putInt` 反转并立即读回，然后还原并读回；每次写操作都在 finally 释放临时 shell 身份。不向产品授予权限，不直接调用 Service、JNI、输入核心或隐藏生命周期 API，也不更改编辑器正文。

两次设置通知可能被合并，实验只要求真实服务 restart 计数增加；不按写入次数虚构回调次数。设置变化也可能真实隐藏键盘或结束输入，因此不能单凭 restart 增加就认定复现了同编辑器保组字问题。

## 必须保持的断言

在触发前冻结实际 host 连接创建计数、已收到命令的连接 serial 和 service trace 序号。独立 test-only 观察器使用公开 Insets、window focus 和 global focus 回调，只记录布尔/计数；任何观察到的隐藏/失焦、真正 `INPUT_FINISHED`、host 连接变化或有界 trace 不完整，都拒绝“同编辑器连续”结论并报告 `interrupted_not_same_editor`。采样和公开回调不是无限精度的显示证明，不能据零计数声称每个未绘制瞬间都可见。

确认持续连接、焦点、served editor 与可见范围后，才用新鲜按钮几何单次真实触摸 i，要求独立 Editable 精确为已接受种子 + shi，且仍有 composing span、可见中文候选。然后触摸当时所见候选，要求最终正文精确为种子 + 该候选且无 composing。没有触摸重试、A11y 点击回退、放宽选区/EditorInfo 保护或输入线程远端读取。

| 报告分类 | 含义 |
| --- | --- |
| `not_executed` | 触发或必要基线未完成 |
| `interrupted_not_same_editor` | 观察到中断或无法证明原连接/可见范围连续；不算同编辑器组字失败复现 |
| `same_editor_composition_failure` | 取得连续性证据后，原精确组字或候选断言失败；仍须回读实际 trace 判断原因 |
| `same_editor_composition_preserved` | 本次设置触发与精确输入/候选闭环通过；不消除原失败或证明所有重启稳定 |

实验 finally 独立关闭观察器、恢复系统设置并读回，保留最初错误及后续清理错误；报告即使先前失败也尝试写出。外层随后仍执行真实系统 IME 切换/退出、两族 UID 规则清理、原系统设置恢复和清合成包，任何失败都不能 PASS。实验报告器拒绝缺少任一实际证据、连接变化、可见性缺口、错误设置值、无回调观察、错误正文及范围夸大的报告。

## 本机验证与实际 CI 的区别

新严格报告器四项测试覆盖完整正向、隐藏/失焦/结束输入分类、缺失/伪造证据与专用设备保护。既有候选 14 项 Python、SelectionEchoTracker/事件环 JVM 回归及全部独立测试宿主 Java 编译保留。没有模拟器结果时，仅可报告测试代码已提交/本机编译通过；不得按方案推导失败、修复或手机行为。

原始线索与官方源码身份见[安全调查记录](evidence/api35-configuration-restart-20261107/README.md)。框架路径仅构成实验依据，未运行前不得视为唯一根因。
