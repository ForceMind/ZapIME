# PR96 新固定源码的原生恢复验收：有成功，也保留真实失败

固定 PR head `9b801ff947486f5418e9ddc84a88941d1cf3eda6`，local `dd3b237e1d53bd208c8e3d4e97b207f2b0449aa8`。实际构建 checkout 为 `436b267cbbc5885ecdf58a7515ef0323fbb6f5e8`（父 d38e6ce + 9b801ff）；Git API 与本地都证明完整 tree 为 `3995757340519d1b21fc5dfb3b2b89367673d087`。本目录仅报告此源码与 attempt1，没有重试。

候选 workflow `42513949719` 实际整体 FAILURE，不能用 host/API28/API36 成功代填：

| 作业 | 实际结果 | 范围 |
| --- | --- | --- |
| 构建 `128832759409` | SUCCESS | 原 build ZIP 720B 已下载核 SHA；两 ABI 产品与两个 harness 的字节/SHA 有固定源码 BUILT 元数据。118MB inputs 含 APK/模型，不进 Git，也没有宣称独立下载复验 APK。 |
| Host `128832759528` | SUCCESS | 原 ZIP 42,835B 已核 SHA；三份冻结质量报告按原指标独立重算，语料/source/model/dictionary/runtime/personal/call-count 身份通过；不是 Android 或真人准确率。 |
| Candidate API28 `128835023588` | SUCCESS | 原 ZIP 99,036B 已核 SHA；同源严格验证器重验14项方法、真实系统IME/当前revision评分、所见候选精确入Editable、系统IME恢复、服务实际退出、规则移除及合成数据清理。API<31没有四个新方法，不借API36填充。 |
| Candidate API36 `128835023474` | SUCCESS | 原 ZIP 108,962B 已核 SHA；14项方法及4个新增 unattached Service/Editable方法均过，真实系统IME和上述清理/退出门禁严格复验通过。新增4方法不等于远程Binder或框架重启证明。 |
| Candidate API35 `128835023489` | FAILURE / 原生 NOT RUN | sdkmanager 安装 `system-images;android-35;google_apis;x86_64` 时报告 `Error on ZipFile unknown archive`，AVD没有启动，suite未执行；随后没有报告文件而upload失败。完整decoded原job日志与SHA保留，无35报告ZIP，不能称方法或输入逻辑失败。 |
| 配置实验API35 `128835023414` | FAILURE | 已进入真实触发阶段，连续编辑器没有隐藏/失焦/INPUT_FINISHED；二次快速restart使新鲜ticket被 `fresh_superseded` 拒绝，组字保留目标未完成。下详，不把它改为成功或和SDK安装失败混为一项。 |

## 配置实验的实际失败

原 ZIP 78,131B/SHA `de156273b65c2c89e7f402a56a3c7ead0bd6ddd9edd252bdf629f78f5e1adbdc` 完整保留。同源 `verify_configuration_report` 仍严格拒绝原报告。报告自身的 classification 仍是默认 `not_executed`，但本次**实际执行**了初始可见Insets确认、7次真实按键、设置逆值/恢复值读回及两次framework restart；不能沿用旧callback0那轮的解释。

原报告记录：before restart0→after2，IC计数2→2/command connection1→1，hidden/focus-loss全部0、trace complete、无INPUT_FINISHED。实际事件时间是uptime毫秒：`50506 INPUT_RESTART → 50507 RESTART_FRESH_BEGIN → 50519 INPUT_RESTART → RESTART_FRESH_REJECTED → ABANDON_COMPOSITION`。失败时decision为 `fresh_superseded`；host50525记录finish，原 `你好sh` 正文长度4保持、组字标记结束。无新的空初始化finish，也没有 fresh validated。实验停在 i/候选提交之前，reason=`configuration_fresh_verification_invalidated`，设置已恢复。

这与源码“第二次相同编辑器重启明确退役pending ticket”的保守路径一致，未据此证明数据覆盖或全部历史间歇失败同一根因；但是本轮连续组字保留目标失败，不能验收。原报告/失败时Service/host生命周期均保留，没有修改字段制造通过。

## 原件、复核与边界

`archive-manifest.json`给出五份原 ZIP 的artifact ID/原大小/SHA，`archive-members.json`逐成员核对。ZIP全部检查为安全报告/日志/合成截图，不含APK、模型、PCM、密钥或录音。API35原job日志按UTF8 decoded原字节保真gzip（mtime0），不把它当作未发生的Instrumentation记录。

`strict-validation.json`记录实际验证结果。模型方法使用同源 `android_candidate_ranking_report.verify_report`；API36新增方法使用 `verify_restart_methods`；系统IME使用同源 `android_candidate_ime_smoke.verify_report`，另核suite、双栈UID规则、fatal为空、恢复设置、数据清理及真实服务退出。配置失败由同源严格报告器原样拒绝。

三份host质量复核仅调用已有 strict_quality.py 的 `validate()`，没有调用其带旧head常量的main。调用前逐字节比对当前源码和已初始化词库工作树中的runner/统计/ranking/model lock与三份冻结语料，并核两处MSIME Gitlink均为相同固定提交；这样复用相同验证输入，不借旧报告或旧head代填本次结果。原六份host报告都在原ZIP内；另对照源测试的15个native用例、36个JVM JNI计数、oracle固定夹具/代码SHA与同运行库误差，均复验通过。长页量化batch的原阈值是0.1，本次实际0.017479419708251953，未误报为0.001；普通feed原阈值2e-5、实际0，oracle同库差0。未重新推理、修改阈值或改语料。主机、独立方法、系统IME、框架重启与手机/ARM仍是不同证据。

`other-jobs-snapshot.json`只是后续整轮快照：full common APK与部分focused已成功，其他下游仍排队/运行；Source与service是元数据成功观察，未在此声称原报告事务/模型复播。完整Android后续与focused最终结果将继续另记，不能把此部分候选结果称整套新原生通过。原失败没有覆盖，原源码Alpha标签/资产未动。
