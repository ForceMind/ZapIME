# PR #96 首次候选工作流：下载门禁失败，模拟器未执行

固定远端 head `42f4562193f8d13a684aa7b656ca2adaa27de216`，工作流 [42464855505](https://github.com/ForceMind/ZapIME/actions/runs/42464855505)，attempt 1。实际 checkout `49186be44237965669f314ebbe768f3684297a45` 的 Git tree 与 head、local `a4d53bc` 均为 `02b21f6d4e1897bbde801b5096c0bd59fa5e258e`，已逐项读取 Git API 并核对两份 job log 的 checkout 记录。

| 门禁 | 实际结果 |
|---|---|
| build job `128681882858` | step 8 在 `prepare_candidate_model.py:150` 的 HTTPS open 收到 HTTP 504；APK 构建及 build metadata 生成均未开始 |
| host job `128681883016` | 5 配对指标、15 candidate 报告、5 schema2 配置报告、4 host runtime 单元测试先通过；step 7 同脚本 HTTPS open 收到 HTTP 504，模型/JNI/质量推理均未执行 |
| API28/35/36 候选及四项新 Editable 方法 | 依赖 build，全部 skipped；不得替代为旧源码结果 |
| API35 schema2 配置实验 | 依赖 build，skipped；不是已观察到的同编辑器保组字通过或失败 |
| Artifact | GitHub API 返回空列表；不存在可下载核验的本轮 ZIP/APK/report |

两处首失败均发生于固定候选依赖下载的 `OPENER.open(..., timeout=60)`，日志没有输出当时的具体 dependency 名称或最终 URL，不能仅凭 traceback 断言是哪一个远端资产或唯一根因。模型/ORT pins、许可与 SHA256 均未改动。本轮未重试。

`job-*.decoded.log.gz` 是 GitHub connector 返回的完整 **decoded log 文本**，按 UTF-8 原样保存并使用 mtime=0 压缩；它们不是原始 Actions 日志 ZIP。`decoded-log-provenance.json` 同时记录解压后文本与 gzip 的 bytes/SHA256；failure excerpt 仅便于定位，不能替代完整日志。`workflow-run.json`、`jobs.json`、`artifacts.json` 和两份 Git commit API 响应保存本次只读返回。`readback.json` 记录本地复核与严格未执行范围，`manifest.json` 覆盖本目录除自身外的文件。

该失败未进入产品运行，因此不能证明或否定 fresh restart 修复、系统真实触摸、候选身份、Editable、服务退出、schema2 连贯性或 ARM 真机验收。
