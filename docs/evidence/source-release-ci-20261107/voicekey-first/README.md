# PR #82 首轮 VoiceKey workflow 失败记录

2026-11-08 UTC 读取 [run 42440405925 / attempt 1](https://github.com/ForceMind/ZapIME/actions/runs/42440405925)。该 workflow 最终为 `failure`，唯一失败 job 为 [128609038474 · Local sync preparation API 28](https://github.com/ForceMind/ZapIME/actions/runs/42440405925/job/128609038474)，失败步骤为 `Offline stateless JNI, Keystore and recovery lifecycle`。

## 原始身份

| 项目 | 固定值 |
| --- | --- |
| run 顶层 head_sha | `67da3cc5f84111b2a20058364cecdc09006bfb0c` |
| head tree | `e9f4dde64a3f1018880b0ac589ab5ea8a869d6d9` |
| 失败 job 实际 checkout | `aa329001b130a5ce564d228c481e8f32e44f4aaf` |
| checkout tree | `e9f4dde64a3f1018880b0ac589ab5ea8a869d6d9` |
| checkout parents | `5621ab0e81780ca58c6cf74e97c93928a10f981a`、上述 head |
| 环境 | 临时 GitHub 模拟器 / API28 / default AOSP / x86_64 / emulator-5554 |

实际 checkout 同时由 job 的 `git log -1 --format=%H` 和 Git commit API 核实。读取时 PR 已向前推进，run API 内嵌的当前 PR head 会变化；不能用该字段覆盖本次顶层 head_sha 或 job checkout。

## 已证实的失败范围

job 日志记录 `scripts/android_sync_preparation_smoke.py:45` 抛出 `RuntimeError: Local sync preparation instrumentation failed`，随后退出码为 1。构建、产品 APK / model-free harness 检查及模拟器设置步骤此前成功；日志还记录了精确产品 UID 的 IPv4 / IPv6 拒绝规则校验。

为查清具体仪表化阶段，实际读取失败 artifact **13600749254**（`ZapIME-local-sync-preparation-API-28`），在内存核对 12,459 字节 / SHA256 `c7d7759c6138cac0af34728dffce838ab48b31f160266fb4083657c8a78377dd`，只检查安全的 instrumentation 与 JSON 状态，未保存该 ZIP 或恢复页面图像。固定 instrumentation 错误为：

```text
FAIL Android voice at local sync recovery system documents: Exception synthetic sync preparation UI case: actual SAF open
```

`sync-preparation-ui-report.json` 为 `FAIL`：前面的 `fresh_main_fixture`、`normal_create_confirmation`、`actual_saf_export`、`actual_native_recovery_identity`、`recovery_code_confirmation` 均通过；`actual SAF open` 为失败项，异常类型 `IOException`，`cleanup_completed=true`。同 artifact 的本机准备 11 项、密钥登记 14 项、传输 JNI 12 项均报告通过并完成清理，`failure-scope.json` 记录 `sensitive_ui_dump_collected=false`，`crash.txt` 为空。

已证实失败位于合成恢复 UI 的实际 SAF 打开验收项。现有证据尚不能确定是测试自动化、系统文件提供程序环境还是产品逻辑的唯一根因。日志较早出现一次模拟器加速旋转设置的 `device not found` 警告，随后继续安装和网络隔离校验；不能据此将 SAF 失败归因于该警告，也不能将清理成功视为输入 / 恢复通过。

同一 run 的产品构建、VoiceKey-only API28 / 35 / 36、同步准备 API35 / 36，以及实际 Android HTTPS API28 的 job 元数据均为 `success`。本次 workflow 失败未提供 VoiceKey 模型识别回归的证据；这些其他 job 的产物未在本目录重新验收。

## 保留日志与范围

[api28-sync-preparation-job.log.gz](api28-sync-preparation-job.log.gz) 保存 GitHub connector 返回的完整解码 job 日志，以 UTF-8、gzip mtime=0 归档；平台 token 已显示为 `***`。检查未发现私钥或带查询参数的临时下载 URL，未收集恢复码、恢复文件或通用页面转储。这里的摘要对应 API 解码文本及归档 gzip，不代表 GitHub 日志 ZIP 的二进制摘要。

| 文件内容 | 字节 | SHA256 |
| --- | --- | --- |
| 解压后的 UTF-8 job 日志 | 193,548 | `54eaa8215c99656b90118d207537f3203fa5e369828817b300ed4bfced959049` |
| 归档 gzip | 34,126 | `b66a2bfc6892e87a0ce5585c3dd06363a159567f1b36ad4f8cd47cab7be3fba0` |

本次仅只读调查并保留首轮失败；没有运行手机、改动产品或重跑设备。后续 head / workflow 的成功或失败须另行记录，不能覆盖本记录。
