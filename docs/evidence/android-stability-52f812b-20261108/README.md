# PR #102 固定源码实际验收与独立报告复验

核验日期：2026-11-08（客户端日期）。唯一失败作业的重跑于 2026-11-09 06:55 UTC 完成。

## 精确源码身份

PR head `52f812bc269f35ba636997b9c4664c2b83a22154`；实际 Actions checkout `8cf2cae1fcc62827a5c508d38609c131ee6f009e`。Git 对象与两个 build metadata、全量构建/重跑 checkout 日志交叉核对：两提交 tree 均为 `2c73b5abe92214ad97eb0104f11911fa6a9d3cb6`。

原始三条工作流：

- 候选专项 [42517974445](https://github.com/ForceMind/ZapIME/actions/runs/42517974445)，attempt 1，六作业成功。
- 完整 Android [42517974761](https://github.com/ForceMind/ZapIME/actions/runs/42517974761)，attempt 2，最终六作业成功；只有 VoiceKey API35 被重跑，其余成功项沿用 attempt 1，不宣称新跑了六次。
- VoiceKey focused [42517974542](https://github.com/ForceMind/ZapIME/actions/runs/42517974542)，attempt 1，八作业成功。

同一 head 的其他五条工作流最终也成功；精确列表见 `current-ci.json`。Source Alpha 工作流成功不等于发生新公开发布。

## 实际报告核验

`verification.json` 中 41 组独立报告复验全部通过。这些是原 CI 结果的只读回放，不是再次运行设备测试，也不是 41 条新的独立产品用例。

| 覆盖 | 严格回读内容 |
| --- | --- |
| 候选 API28/35/36 | 每 API 14 项真实模型/JNI/MSIME/独立 Editable；同运行库 oracle、模型/运行库/fixture 身份；独立生产系统 IME 真实触摸/当前输入代次/精确 Editable；退出、设置与 UID 清理 |
| 重启方法 API35/36 | 每 API 7 项实际 Android Editable/生产 helper 重入探针；API28 不适用，不虚称七项运行 |
| 配置重启 API35 | 两次实际 restart，同 editor/IC/焦点，新鲜验证成功；`你好sh`→`你好shi`→`你好是`，精确正文与清理通过 |
| 完整 Android API28/35/36 | 每 API 两轮清理后的真实键盘；65 项结构化 JNI/确认/连接/备份/准备/VoiceKey 边界；原 X-ASR 合成解码及系统麦克风 UI/服务退出/UID 清理 |
| VoiceKey focused 与完整 Android 三 API | 每 API 14 模型 + 23 确认 = 37 项，另 5 JNI 边界；真实模型 SAF 单次手指选择/独立时钟 PCM/解码与 watchdog；实际系统 IME、共享文档/键盘策略/UID 清理 |
| 同步 API28/35/36 | 每 API 准备 11、SAF 恢复 UI 10、密钥 14、传输 12、页面 10，共 57 项；严格 schema/范围及清理 |
| API28 实际 HTTPS | before/roundtrip/after 三阶段共 14 项，实际 Go/PostgreSQL 双向传输、错误 SAN 拒绝、临时 CA/系统根库/网络规则/进程/测试包清理 |

使用 `52f812b` 固定源码中的原 validator；完整 Android 的报告核验复用原 `android_voice_smoke.py` 的纯校验代码段，没有执行 ADB/下载/清库。原 validator 与锁的精确副本仅用于重放，源码身份见 manifests。将 `accepted-reports.zip` 解压到独立目录后，在解压根目录执行 `python3 verify_reports.py`，不触碰设备、不使用网络。

## 原失败与重跑

原 full VoiceKey API35 job `128848444057` / artifact `13632636522` 保留 FAIL；它在模型导入前被系统 Launcher ANR 弹窗遮挡，`model_decode_executed=false`。原 provider shape 含 `android:id/aerr_close`、`android:id/aerr_wait`；last-ANR 原件显示 `com.google.android.apps.nexuslauncher` 和无 focused window 的 input-dispatch timeout。不能将该失败改成产品推理成功或删除。

只重跑此作业：新 job `129022697552`，新 artifact `13655606127`，实际模型/Editable/系统 IME/清理均通过。原失败 ZIP 的 SHA256 为 `7512533fab78a5390c4c62c43bf3b36762428fd2ce5268427be818f3b0bfe11f`；新成功 ZIP 为 `53bb1d1ec949b39586023aaa6a714d077ee7204782996ddb9c46564a90a01387`。二者均实际下载；原 ZIP 继续单独保留，归档包只收录原始安全报告及有原行号/哈希的 ANR 摘录。

## 归档结构

完整 311 份小报告/固定校验器/清单原字节在 `accepted-reports.zip`；本目录另复制关键摘要便于审阅。文中的原报告、manifest 与 `verify_reports.py` 路径均相对于解压根目录。原首轮失败 ZIP 以 `original-api35-launcher-anr.zip` 单独永久保留；其中只有临时合成测试设备的诊断。

本轮独立源码审查及143项Rust/JVM复跑记录在 `local-validation.zip`；`integration.json` 记录合并身份，不能把旧 CI 改称合并后重新执行。

## 原件与范围

所有收录报告保持原字节。每个源 ZIP 已实际下载并核对 GitHub size/SHA256，完整成员目录及收录成员的独立 SHA 见 `archive-manifest.json`、`original-member-manifest.json`。API28 报告原来与 APK 混在 248,741,116 字节 artifact `13632357194` 中，已实际下载完整核验后只抽取报告。`accepted-reports.zip` 不包含 APK、模型权重、PCM、私钥、凭据、恢复码、截图或广泛的系统运行日志；首轮完整隔离设备诊断另保存在 `original-api35-launcher-anr.zip`，不与精简验收包的范围混称。

候选 host 的三份冻结合成质量报告及 native/JNI/oracle 原件一并保留，不重新推理、不修改预期或删除伤害样本。它们不是自然用户准确率。此包的 41 组验证没有另行重算 host 质量指标。

七项 Editable 方法探针不是完整 framework/Binder/手机验收；实际配置实验另列。API28 HTTPS 是临时系统 CA 下单模拟器两 profile，不代表 API35/36、手机、公网 R2、配对或生产。自然语音、ARM/S25 资源与能耗、正式签名升级、Apple/Windows 系统输入、完整恢复记录首次上传仍无本包验收结论。配置实验成功也不证明 #38 的所有历史间歇问题都已解决。
