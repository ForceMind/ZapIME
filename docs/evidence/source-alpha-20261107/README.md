# 源码 Alpha 候选专项归档

原执行日期：2026-11-07 UTC。下载与离线独立复核日期：2026-11-08 UTC。

[候选 workflow 42407415284](https://github.com/ForceMind/ZapIME/actions/runs/42407415284) 的 API28 / 35 / 36 均完成 14 项真实模型 / JNI 方法门禁和 schema2 系统 IME 门禁。本目录保留 5 个原始元数据 ZIP、GitHub artifact 大小 / 摘要、可读报告和本次独立复核结果；没有重新运行设备或模型推理。

## 固定身份

| 身份 | 值 |
| --- | --- |
| PR #81 head | `5d976f578a2b11d14b9050ff0ec01e5d18d9e538` |
| head 源码树 | `b9f4c62a752d196b0d1dfad50acb5fc9da73aff5` |
| 本轮构建实际 checkout | `6060ccad8466cc965f177e1f19e2d8e42050b652` |
| checkout 源码树 | `b9f4c62a752d196b0d1dfad50acb5fc9da73aff5` |
| run / attempt token | `42407415284-1` |
| Android ABI / API | x86_64 / 28、35、36 |

`6060ccad` 是 GitHub 为 PR 生成的 merge checkout，父提交为 `7ff77a9ab25d91fc3ec9c61d5d6f96d996c699ae` 与上述 head。已重新读取 Git commit API，核对其树与 head 相同，记录于 [checkout-provenance.json](checkout-provenance.json)。本轮 APK 身份取自原始 [构建报告](build/candidate-ranking-build.json)：ARM64 为 58,745,799 字节 / SHA256 `baabc0f9685da87657dad0778320e24f82115a20c4c2180fb19eb2c88c4dd000`；本归档没有下载或独立重验这些 APK 字节。

此前交付的 fixed8df 安装包有自己的源码、checkout、开发证书与摘要，见 [原验证记录](../context-guard-20261107/README.md)。不能将它改称本轮 5d / 6060 构建包，也不能用本轮摘要覆盖旧试用包身份。118MB 的本轮产品输入 artifact 未重复下载或入库。

## 原始元数据产物

每个 ZIP 均实际下载，核对 GitHub 公布的长度和 SHA256，再检查路径、成员类型、重复名称与展开上限后解压到 `target/source-alpha-20261107`。原 ZIP 保持原字节，逐成员摘要见 [manifest.json](manifest.json)，上游元数据见 [artifact-metadata.json](artifact-metadata.json)。

| 产物 | artifact ID | ZIP 字节 | SHA256 |
| --- | --- | --- | --- |
| [API28](api28/original.zip) | 13588975136 | 97,921 | `080f67c9a887742842269d07618502582765dba6daaaeef7feba5684bff9c46d` |
| [API35](api35/original.zip) | 13588392756 | 105,865 | `44c5dabbba64877b6f72be4fd085ca92dc73d55529a21e0eb67564241167197f` |
| [API36](api36/original.zip) | 13588427727 | 106,875 | `d33a669c8bdb501242857808436ba0c3a6f88e1a25c1b74ea77d5b27d8474181` |
| [主机](host/original.zip) | 13588416673 | 12,237 | `a2ecbc2728b29cc2b6ffefdcd41565d61dd13c2beda77670d2c0055daec5777e` |
| [构建身份](build/original.zip) | 13587967776 | 722 | `c80e7c9345425c9ea0cb6fa2de1397b751d13fbe3b81503e1331abe768a3ce79` |

## 独立报告复核

实际调用仓库的 `android_candidate_ranking_report.verify_report` 和 `android_candidate_ime_smoke.verify_report`，分别验证 API 与精确 token。三 API 均为：

- 方法报告 schema2 / 14 项通过，固定 8M 模型、真实 MSIME、独立同运行库 oracle、原候选身份与 Editable 证据齐全；方法报告仍明确不代表系统 IME 或手机验收。
- 独立系统 IME 报告 schema2 通过。真实按键输入 `nihao` 时空上下文提交计数保持 `0 → 0`，触摸可见候选形成 `你好` 上下文，再输入 `shijie`，当前 revision 排序后将所见 `世界` 精确提交为 `你好世界`。共 11 次拼音按键、1 次种子提交，最终无组字标记。
- `run-summary.json` 核实返回预装系统键盘、实际 ZapIME 服务退出、系统设置恢复、两个合成包首尾清理；方法和系统两类 IPv4 / IPv6 精确 UID 拒绝规则均记录已移除。保留的 crash / runtime-errors 为空，未见目标 App ANR 记录。API28 保留 AOSP 条件，API35 / 36 保留 `pm list packages -s` 的预装系统来源证明。

主机原报告包含 15 组 native 数值 / 资源边界、36 项真实 JNI 以及独立 oracle 的成功证据。质量报告在 Linux x86_64 / ORT **1.24.4** 上完成冻结 v2 的 34 条合成用例；已使用 `candidate_evaluation.summarize` 对保留观测重新计算聚合值：空上下文首选由 17/34 变为 14/34（损害 3），带上下文为 25/34（挽回 8、损害 0）。这不是 Android ORT 1.28.2 的质量成绩，也不是用户输入准确率；无模型重跑、手机性能或能耗结论。

完整复核结果、validator 源码摘要与范围见 [local-verification.json](local-verification.json)。可从仓库根目录重验已归档的两类报告：

```sh
PYTHONPATH=scripts python3 - <<'PY'
import json
from pathlib import Path
from android_candidate_ranking_report import verify_report as method
from android_candidate_ime_smoke import verify_report as system

root = Path('docs/evidence/source-alpha-20261107')
for api in (28, 35, 36):
    method(json.loads((root / f'api{api}/method-report.json').read_text()), api)
    system(json.loads((root / f'api{api}/candidate-ime-report.json').read_text()),
           '42407415284-1', api)
    print('PASS', api)
PY
```

## 同一 head 的 workflow 状态

本次重新通过 GitHub API 读取以下 7 条 workflow，均为 `completed / success`，head 均为上述 `5d976f`。精确读取时间、run attempt、路径和源码树保存在 [workflow-status.json](workflow-status.json)。这里只归档候选专项原始产物；其他 workflow 的完成状态不等于在本目录逐项重新验证其产物。

| workflow | run | 结果 |
| --- | --- | --- |
| 候选专项 | [42407415284](https://github.com/ForceMind/ZapIME/actions/runs/42407415284) | SUCCESS |
| 完整 Android | [42407415282](https://github.com/ForceMind/ZapIME/actions/runs/42407415282) | SUCCESS |
| VoiceKey Android | [42407415283](https://github.com/ForceMind/ZapIME/actions/runs/42407415283) | SUCCESS |
| 共享核心 / Apple | [42407415237](https://github.com/ForceMind/ZapIME/actions/runs/42407415237) | SUCCESS |
| Windows | [42407415301](https://github.com/ForceMind/ZapIME/actions/runs/42407415301) | SUCCESS |
| 同步服务 | [42407415255](https://github.com/ForceMind/ZapIME/actions/runs/42407415255) | SUCCESS |
| 离线语音 CPU | [42407415336](https://github.com/ForceMind/ZapIME/actions/runs/42407415336) | SUCCESS |

## 保留的边界

本轮成功没有解决 [首轮 API35 组字异常](../context-guard-20261107/README.md) 的唯一根因，原失败和同源码复验继续保留。当前仍是开发 / Alpha 源码与合成模拟器证据；ARM / S25 长期输入、自然人声、真实输入质量、手机资源 / 能耗、其他宿主模型和正式签名仍须各自验收。

本目录文字和图像只来自固定合成语料与临时 CI 模拟器；未归档真实用户正文、录音、同步恢复材料或临时下载 URL。
