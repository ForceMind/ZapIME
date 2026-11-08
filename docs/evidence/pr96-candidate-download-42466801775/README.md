# PR #96 新 head：有界下载实跑后仍 504，原生门禁未执行

这是新 head `94b66f3e3ac9c0af92952427d0f3f1bfc27bb6a8` 的独立工作流 [42466801775](https://github.com/ForceMind/ZapIME/actions/runs/42466801775)，attempt 1，**不是**旧 `42f4562` 工作流的重跑。旧[首轮失败原件](../pr96-candidate-download-42464855505/README.md)完整保留。本轮同样先冻结，没有手动重跑 job/workflow。

两份完整 job log 记录实际 checkout `a0add63cc59cd6b3f7956bee4f04c7c805c72c1f`（merge 新 head into main `b9cc2f2e17016b49c5636f91a713ea13f8146691`）。Git API 与独立本地 `0a0b85463ce1115a60224f8d9c87a811a2a1d7b5` 均核到 tree `78d00ce936aafd38b7073e1dc688f502128a0e46`；远端 recursive tree 的路径、模式、SHA 也逐项对照相同。不能把不同提交的旧成功代填本轮。

| 门禁 | 实际结果 |
|---|---|
| host job `128688078317` | 28 项新下载故障回归、5 配对指标、15 candidate 报告、5 schema2 配置报告、4 host runtime 准备测试先通过；step 7 固定模型依赖 GET 最终 HTTP 504 |
| build job `128688078512` | 14 项模型准备回归先通过；step 8 固定模型依赖 GET 最终 HTTP 504，未构建 APK 或生成 build metadata |
| 实际重试行为 | 日志明确 `reason=504 attempt=1 next=2 wait_seconds=1`、`attempt=2 next=3 wait_seconds=2`；最终第 3 次失败沿原 HTTPError 退出，未无限重试或伪装成功 |
| 主机数值 / JNI / 冻结质量推理 | 准备失败，未执行 |
| API28/35/36 候选与四项新 Editable 方法 | 依赖 build，全部 skipped，未执行 |
| API35 schema2 配置实验 | 依赖 build，skipped，未执行 |
| Artifact | GitHub API 返回空列表；本轮没有可下载验证的 APK、native/Editable 或系统 IME 报告 |

host 日志在准备步骤另有更早一次 GET 的 `attempt=1 next=2`，原样保留；不把不同 GET 的 attempt 计数拼成一个超限请求。两个最终错误均在 `prepare_candidate_model.py` 的固定模型文件循环、通过 `build_download_retry.run_with_retry` 进入 HTTPS open。原日志没有打印具体文件名/最终 URL，不能据此唯一确定哪一个远端资产或网关原因。模型来源、许可、尺寸和 SHA 门禁没有放宽。

`job-*.decoded.log.gz` 是 connector 返回的完整 decoded UTF-8 日志文本，mtime=0 压缩，**不是**原始 Actions 日志 ZIP。`decoded-log-provenance.json` 同时记录原文本和压缩文件的 bytes/SHA256；failure excerpt 只便于定位，完整原文本仍在 gzip 中。Git API 返回、空 artifact 列表、各 job 最终状态、严格未执行范围均另存；`manifest.json` 覆盖本目录除自身外的文件。

本轮证明新的有界重试代码确实进入了真实下载路径，并在最终失败后保持失败。它没有提供 fresh composition 修复、系统真实触摸、候选身份、Editable、服务退出、配置连贯性或 ARM 真机的新验收证据；28 项下载单元测试也不能替代这些门禁。
