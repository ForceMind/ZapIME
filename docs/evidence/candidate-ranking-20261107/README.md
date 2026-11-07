# 候选增强验证记录 · 2026-11-07

本目录只保存固定合成输入、公开模型及公开生产词库的验证资料，没有用户编辑器正文或个人词库。

## 主机实际执行

| 原报告 | 证明内容 | 不能据此宣称 |
|---|---|---|
| `host/native-numerical-report.json` | 真实 8M 与固定上游 Python feeds 的 15 组数值、缓存、边界检查 | Android / 手机性能或输入准确率 |
| `host/jni-report.json` | 生产 JNI + 生产 native + JVM 的 36 项实际检查 | Android 系统键盘触摸已经通过 |
| `host/engine-reading-metadata.json` | 固定引擎和实际生产库的 pinyin/canonical/source/匹配元数据 | 从汉字数猜测消费长度 |
| `host/production-pool-before-exact.json` | 真实推理揭示补全项过度前置：世界各地、天气预报、你好啊 | 其中 `passed` 表示机械链路成功，不表示语言质量合格 |
| `host/production-pool-after-exact.json` | 修复后只重排精确池，3 组合成输入串联真实提交 | 以 3 例代替大规模质量评测 |
| `host/production-pool-reproducible.json` | 仓库内正式探针从另一 cwd 重跑 3 组链路，明确 `mechanical_passed` | 模型语言准确率已被评估 |

前后报告保留不同实际 core 二进制 SHA。修复前没有保存完整源树快照，报告明确记录此限制；不得把基线 Git SHA 当作未提交实现的完整身份。修复后另有 `ranking.rs` 内容 SHA。固定生产拼音词库 SHA256 为 `7c672458e6068c22c07b33b146c5a07f4e734959abc3bddd2784fe18a7582a76`，模型 SHA256 为 `e036febfb8f7bdf941f1637be127ff539ea95b937a4c2a27f4c6ab897138fc6c`。

修复来自真实观测：`candidate_answers_key=true` 只证明选择可结束组字；词典前缀补全也可能满足。生产元数据提供实际完整读音，因此在此基础上增加精确读音匹配。`shijie/tianqi/nihao` 可排项由 23/10/5 缩为 20/7/2，补全保留原槽。选择继续按 engine ID，不按相同文本猜身份。

数值测试使用 Linux CPU、ORT 1.24.4、NumPy 2.5.4。普通页相对参考最大误差为 0；较长候选分批后的最大平均 logP 差异为 0.035329580307006836。最大合成页实际分 17 次图调用并淘汰缓存 3 次，保留 504 个候选前缀，未超过 512 的容量。分批量化差异如实记录，没有以结果反向放宽门槛。

主机耗时只描述该执行环境，不能用作 Android 1.28.2、ARM、S25、功耗、P95 或 RSS 指标。Android 方法与实际系统键盘有各自独立报告，只有对应固定提交的真实 CI 成功后才补入。

可复现入口为 `integrations/candidate/tests/test_host.sh` 与 `integrations/candidate/tests/default_pool_probe.py`；所需词库、头文件、原生 core 和 Python 环境命令在该目录 README 中。正式探针记录脚本、锁文件、模型、词库、core、运行库及 scorer 二进制身份，`language_accuracy_evaluated=false` 和 `android_performance_evaluated=false` 不可改写为成功。
