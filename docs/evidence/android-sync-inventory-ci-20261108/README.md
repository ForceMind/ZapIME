# Android 本地同步状态页：首轮结果、夹具修复与下载阻塞

日期：2026-11-08（America/Los_Angeles；原 CI 时间戳为 UTC）。

**本页不是全部验收通过的证明。** [PR #104](https://github.com/ForceMind/ZapIME/pull/104) 已实现本地只读状态页，当前 head `16c7d89` 保持 draft。首轮 API35/36 页面通过，API28 fixture 失败已作代码修复；修复后新 CI 的固定依赖持续 HTTP504，三 API 尚未执行。没有合并该产品切片，没有发布新 APK Release 或移动 Alpha 标签。

## 固定源码与报告身份

| 层级 | 提交 / 源树 | 范围 |
| --- | --- | --- |
| 首轮本地 | `4ed8ca0da1ff2474af57123cd5b7b48ea3a480e8` | 初始 Android inventory 实现与本机证明 |
| 首轮远程 | `d6f98a8025aae4025583ed6577a23e1a580c7fb5` | PR #104 首轮 head |
| 首轮实际 CI | `e6bf431fe2c8a7e719d5564a41817c1ef833fb3a` | 上述三者 tree 均为 `88646a7442ae6941282ff06e30237b67797fe781` |
| 夹具修复本地 | `72ddf646b8cc3dd9db1b819891aa5cda8cb0d536` | 只改 Android 测试夹具、Rust 语义回归和模块文档 |
| 夹具修复远程 | `16c7d89ff7faa6c3b4979074ed9301cf25be9544` | 本地/远程 tree 均为 `a6f1e6160edb3763859d62583d63e9f5f22dbd88`；远程 parent 为 d6f |
| 修复后实际 CI | `4c182b24347d08c2008863a29f369a7089eaeca2` | parents 为 main `a199d56` 与 head `16c7d89`，tree `a73b74ca12b199c282fc8afc55256e6d0578753b` |

最后一行比 feature head 多出的19个路径全部是 #103 已合并的文档和证据，产品/测试源码不变。不能把本地 commit metadata、远程 head、PR checkout 或最终 main SHA 混写为同一提交。

## 首轮实际结果

[first-attempt-d6f98a8.zip](first-attempt-d6f98a8.zip) 保留58个文件，包括三 API 原 artifact ZIP、当时严格 validator、逐份重验、原始构建元数据、8条 workflow/29个 job 最终状态、三种失败与独立审查。完整 ZIP SHA 与逐文件 SHA 分别由本目录及包内 `SHA256SUMS` 固定。

首轮8条 workflow 为5 SUCCESS、3 FAILURE。新同步专项 [42577078955](https://github.com/ForceMind/ZapIME/actions/runs/42577078955) 的实际结果：

| API | 准备 | 恢复 UI | 身份登记 | transfer schema2 | pages schema2 | 结论 |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 28 | 11 PASS | 10 PASS | 14 PASS | 15 PASS | `fresh_main_prepared_fixture` 报 `SQLiteException` | 前50项通过；12项页面未完整通过 |
| 35 | 11 PASS | 10 PASS | 14 PASS | 15 PASS | 12 PASS | 62项通过 |
| 36 | 11 PASS | 10 PASS | 14 PASS | 15 PASS | 12 PASS | 62项通过 |

三个 transfer 报告确实执行了新增只读 JNI 分页、完整绑定/坏输入拒绝和冻结/ACK/下载零回声状态保护。35/36 的实际非导出 Activity 测试使用含6条墓碑的夹具，验证40+4条分页、仅保留当前页、无正文显示、末页游标、实际 SyncWorker 排队取消、离页清空、迟回拒绝、全表业务状态与 vault 字节不变；JNI探针另行严格检查6条墓碑。按钮通过生产 `View.performClick`/对话框控件激活，不是物理手指验收。UID 双族规则及各自清理独立验证，不能以清理成功覆盖失败。

另外两种首轮失败分别保存：

- [Candidate API36 job 129029859606](https://github.com/ForceMind/ZapIME/actions/runs/42577078987/job/129029859606)：真实系统 IME 在第一次 `shijie` 的首键失败，独立 Editable 保留已提交的合成“你好”。host 的第6键 `attempt` 在稳定几何等待之前记录，不能当 DOWN 时间；两次注入返回成功也不能证明产品 click/dispatch 已执行。附近确有 restart、epoch推进和视图重建，缺少触摸/派发时间，根因未确认。method 与全部恢复/清理通过不覆盖输入失败，Issue #38 保持 open。
- [完整 Android API35 job 129031760719](https://github.com/ForceMind/ZapIME/actions/runs/42577078966/job/129031760719)：APK 安装后在 `adb root` 返回1，未启动键盘/语音验收。原日志没有具体 root 拒绝文本；不可与上述实际输入失败混为同一故障。

原头没有重跑；后续源码成功也不能改写这些原件。

## API28 测试夹具修复

旧夹具用 Android framework SQLite 直接写入 Rust 创建、含 `json_extract` 表达式索引的 `records`。修复改为同一个真实生产接口链：独立自有临时库编辑43条（6墓碑）→导出 records-only 归档→导入新建的空主夹具→编辑第44条形成唯一待发操作→原 stage 流程冻结。没有新增测试专用生产 JNI，没有改索引、当前页/取消断言、报告 schema 或严格 validator。

[fixture-local-validation.zip](fixture-local-validation.zip) 保存固定72dd的独立审查、Rust目标4/4和 strict Clippy 原日志，以及独立 SQLite 对照的源码/schema/log/摘要。其他本机验证为20个 voice Java 源编译、schema2报告单测10/10、fmt 和差异检查通过。

对照使用同一固定 SQLite 3.53.2 与生产 schema：关闭 JSON 函数时，只读 snapshot 成功而 INSERT 报 `unknown function: json_extract()`，行数不变；开启 JSON 时同 INSERT 成功。这支持修复所针对的兼容性机制，**不是 Android framework 实测**。原 API28 报告没有详细 SQL 错误，因此平台的精确根因仍是推断。新 Rust 回归通过实际 InputSession 请求验证43导入+1编辑、40+4页、6墓碑、唯一操作、冻结字节与两个生产索引；不代替 Android 运行。

## 修复后的下载阻塞

[fixed-head-download-blocker.zip](fixed-head-download-blocker.zip) 保存新 head16c 的原 CI 日志、checkout、最终运行状态和云端对空目录调用原获取函数的失败 JSON。原固定模型/词库 URL、大小/SHA、TLS 与三次有界获取规则均未改动，也没有换镜像或用旧包代填。

新8条 workflow 已最终完成：3 SUCCESS（源码准备、离线语音 CPU、同步服务），5 FAILURE。23个 jobs 中6 SUCCESS、11 FAILURE、6 SKIPPED；11个失败包含6个上游下载 HTTP504和5个缺构建产物的下游失败。focused/candidate 的实际 Android 矩阵没有执行，不能将 skipped 或 missing artifact 归为页面断言失败，也不能视为通过。

| 运行 | 本轮结果与边界 |
| --- | --- |
| [focused 42579960224](https://github.com/ForceMind/ZapIME/actions/runs/42579960224) | `fetch_resources.py` 连续三次504，未构建产品，三 API sync/VoiceKey/HTTPS 跳过 |
| [候选 42579960257](https://github.com/ForceMind/ZapIME/actions/runs/42579960257) | host/build 固定候选模型下载504，未运行系统 IME 或配置实验 |
| [完整 Android 42579960293](https://github.com/ForceMind/ZapIME/actions/runs/42579960293) | build 固定模型下载504，后继缺 APK，未运行键盘/语音 |
| [开发宿主 42579960239](https://github.com/ForceMind/ZapIME/actions/runs/42579960239) | 根 Rust 作业通过；Apple native 固定词库下载504 |
| [Windows 42579960295](https://github.com/ForceMind/ZapIME/actions/runs/42579960295) | TSF 构建前固定词库下载504，不能算系统输入结果 |
| [源码准备 42579960234](https://github.com/ForceMind/ZapIME/actions/runs/42579960234) | SUCCESS；publish 条件跳过，没有新发行 |
| [离线语音 42579960285](https://github.com/ForceMind/ZapIME/actions/runs/42579960285) | SUCCESS；CPU/合成测试范围，不是新 APK 或手机 |
| [同步服务 42579960236](https://github.com/ForceMind/ZapIME/actions/runs/42579960236) | SUCCESS；服务端范围，不补填 Android Activity |

源码 prepare job129036086767 实际执行 Rust145项、fmt与严格Clippy并通过。开发 workflow 的 Ubuntu/macOS 各145项、Windows142项及其fmt/严格Clippy也全部通过；Windows少3项已逐名核对为 `#[cfg(unix)]` 的 sync_writer symlink/parent_alias 两项和 workspace symlink_database 一项。没有把这3项称作执行后的SKIP，也不把主机成功当原生Android通过。原日志及计数摘要在下载阻塞包内。

没有在持续504时循环重跑。下一次核对当前 head 和原固定来源恢复后，再执行同源码原生门禁，所有原失败继续保留。

## APK 检查与交付边界

[first-build-package-checks.zip](first-build-package-checks.zip) 仅保存首轮 d6f/e6bf 的构建元数据、独立包校验报告与日志，不含 APK。原 [artifact13655739022](https://github.com/ForceMind/ZapIME/actions/runs/42577078987/artifacts/13655739022) 的完整 ZIP 为118235404字节，SHA256 `6b692969c952a2daf48bf268dfca165732e751f18d50b5fb0245741d5fdb3d23`。

| 产品 APK | 字节数 | SHA256 |
| --- | ---: | --- |
| ARM64 | 58770375 | `3b540a41a1d9c9689cb7a5a6871a5fc63b65246b371c46291065ba66f69aa4b8` |
| x86_64 | 60081370 | `b8035dd0d7522308bee797a3ec791e1c339d7f30942ebc5194a12b32683a0e27` |

两产品包同证书 SHA256 `c408f186d577a009c0cd9c34e2b2d73b468e77b1cab34a07871e31d0bcaf2080`，v3签名、16KiB ZIP对齐、固定模型/JNI/许可/manifest、新Activity/JNI引用都已独立检查。这不是修复后16c的新构建，不是新源码三API通过或正式签名/可恢复覆盖升级证明；本页不替换已固定的试用指南。当前开发证书与旧候选不同，不能由包可校验推导可以覆盖安装。

本轮没有操作用户手机、电脑、服务器或DNS。Issue #77 的恢复存量首次上传仍未实现；页面只解释本地证据，不能自动把 untracked 变成上传操作。公开 `v0.1.0-alpha.1` 的源码和三个资产未变，ARM/S25、自然语音、长期质量/能耗、正式签名及跨设备恢复仍独立待验。
