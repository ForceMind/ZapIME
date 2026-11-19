# ORT 固定头文件恢复与原 CI 失败 · 2026-11-19

日期使用 America/Los_Angeles；请求原件内时间为 UTC。起点为实际 main
`e2877a85ff2eb3b9eeba8eded83cdd014d5a0ebf`，tree
`192fc3651122d90483852b240c9d8616e3a8b7ce`。本目录记录构建依赖的恢复，
不能作为自然语音、手机或稳定版本验收。

## 原失败及最终回读

[previous-ci-audit.zip](previous-ci-audit.zip) 保留六份完整失败日志、run/job
API、实际 checkout/tree、汇总及包内 SHA256SUMS。日志是连接器返回的原样
UTF-8 解码文本，不是声称下载了原始 GitHub 日志 ZIP。

| 固定源码 | 最终工作流结果 | Android 范围 |
| --- | --- | --- |
| main `e2877a85` | 5 PASS / 1 FAIL | full Android [44318930196](https://github.com/ForceMind/ZapIME/actions/runs/44318930196) 构建失败；设备步骤未开始 |
| PR #119 head `0bd3e31d` | 6 PASS / 3 FAIL | focused、candidate、full Android 均在头文件准备阶段失败 |
| PR #121 head `dc8743e4` | 4 PASS / 2 FAIL | full Android 构建失败；development 的 Apple job 未执行并取消，原因未知 |

六个首要 job `134852376759`、`134847269309`、`134847243322`、
`134847243780`、`134848656090`、`134851968927` 都在原
`prepare_candidate_model.py:302` 的头文件下载循环收到 HTTP404。原日志没有
打印具体文件或 URL，所以不能断言六次都是同一个文件。下游缺 APK artifact
及跳过的设备作业不算产品运行时通过或失败。

PR #121 的 Apple job `134851968108` 是 cancelled、runner_id=0、steps=[]，
日志接口返回 BlobNotFound；没有证据认定编译失败。相同 tree 的 main
[development 44318931165](https://github.com/ForceMind/ZapIME/actions/runs/44318931165)
随后通过。上次记录的“仍运行”现已补齐，历史失败不改写。

## 四份原件的恢复

[availability-and-hash.json](availability-and-hash.json) 是八次实际 HTTPS
请求及 SHA/长度记录：原 Microsoft 四个固定 URL 全部404，Intel fork
同一完整提交的四个地址全部200。实际取得的三个头文件和 MIT 许可证总计
551824 bytes，逐项等于**恢复之前**的 `candidate-model-lock.json`。该锁
整文件 SHA256 仍是 `cc7623268feb96a669a94671cfc406497a52a7b4ce6e6f9f4eecdaa89fbb5b01`。

[intel-original-commit.json](intel-original-commit.json) 记录同一提交
`33ca9628233dc8f002435e868d4c2e9f82766ca1`、tree
`7841f821d01a42a4ae981810242278258898e61f`。API 的 verified 字段是 GitHub
报告，本次没有独立本地验 PGP 签名。恢复不依赖 fork 的最新分支或新计算的期望值。

完整原件、Microsoft 版权及原 MIT 许可保存于
[third_party/onnxruntime-1.28.2](../../../third_party/onnxruntime-1.28.2/SOURCE.md)。
构建先核验全部本地原件，再使用既有 staging/原子目录发布；即使旧输出有效，
本地原件损坏也必须失败。缺文件、变字节、符号链接和未知输出不触发网络回退。
原锁 URL/版本/commit/长度/SHA、APK 运行库、模型和 API24 使用范围均未变化。

原 Issue #120 中 release/tag 可见性的说法应理解为历史搜索索引；本次实时
官方仓库不可访问，根因尚未建立，不能称删库、迁移或安全事件已经证实。

## 独立 Linux 诊断库仍受阻

[linux-runtime-source-investigation.zip](linux-runtime-source-investigation.zip)
保留官方包索引、精确版本请求、SourceForge 两条404原响应与元数据及其它候选
发行清单。未取得原 `onnxruntime-linux-x64-1.28.2.tgz`（9128991 bytes，
SHA256 `d7209b8751b27b862b0c76332c2e20e203396edb5dab700ecf4bb485cf147415`），
没有执行或接受相邻版本、另编译 glibc 包作为原件。

这项原 Linux 1.28.2 质量对照仍须严格获取原锁文件；头文件恢复不代表它已恢复。
`scripts/prepare_candidate_host_runtime.py` 与所有评测断言保持原样。
[Issue #120](https://github.com/ForceMind/ZapIME/issues/120) 继续追踪此边界。

## 本机验证与后续 CI

[local-validation.json](local-validation.json) 记录实际命令及完整输出：资源
21/21、既有下载28/28、catalog检查、实际复制头文件后 C11/C++17 语法编译。
独立代理重跑前两组并审查既有15项下载/校验/发布函数，确认原实现没有改变。
这些是主机构建完整性结果；实际 Android 构建、JNI/模型、系统IME与退出清理
须看本切片 PR 的固定 head/checkout 与原始 CI，不能从主机测试推断通过。

没有修改发布计划、移动 `v0.1.0-alpha.1` 或覆盖三个原附件。
