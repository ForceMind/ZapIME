# 2026-11-07 候选质量与主机资源基线

## 已实际执行的范围

在 Linux x86_64 / Rust 1.97.1 release 核心、固定生产拼音/英文词库、固定 8M 权重上执行。Python 环境严格使用已锁定 ORT1.24.4；另行下载并校验官方 Linux1.28.2 库，以同一个 native probe 独立复验。报告记录实际运行库版本和 SHA。9个逻辑CPU，native固定2/1线程、关闭spinning；不是Android/ARM、S25或真实用户语料。

语料 v2 共有34个唯一合成输入。每种上下文条件是同一批输入的配对对照，不能合成68个独立样本。没有依据结果增加或删减用例，也没有改变可接受答案；v1两处编码笔误的版本化修正见后文。

| 运行库 / 条件 | 基础首选命中 | 重排首选命中 | 基础 / 重排 Top3 | 首选挽回 / 损害 | 目标缺失 |
| --- | --- | --- | --- | --- | --- |
| 1.24.4 / 空上下文 | 17/34 | 14/34 | 28/34 → 27/34 | 0 / 3 | 0 |
| 1.24.4 / 冻结上下文 | 17/34 | 25/34 | 28/34 → 30/34 | 8 / 0 | 0 |
| 1.28.2 / 空上下文 | 17/34 | 14/34 | 28/34 → 27/34 | 0 / 3 | 0 |
| 1.28.2 / 冻结上下文 | 17/34 | 26/34 | 28/34 → 30/34 | 9 / 0 | 0 |

两库带上下文的具体排序存在差异，不能把旧host预期分数用于Android的数值oracle。本切片证明工具能发现退步和版本差异，不证明所有输入都会改善。补全/部分输入边界中未提升的目标仍如实计入结果。

1.28.2 本轮进程加载54.45ms，scorer进程VmHWM54,620KiB、最终RSS38,896KiB；空上下文/带上下文的32次清缓存IPC评分P95分别5.48/6.26ms。1.24.4相应P95为3.22/8.54ms。不同轮次受主机负载影响，不对速度作优劣判断；这些数值也不包含Android防抖、排队、按键、绘制或上屏。

## 证据身份与失败保留

- 语料 v1 首次推理前已发布 `75ae851acb570abbd5295aafa52eda41b33f9c2b`，SHA c78d359e6e396befcddbf16eab286b8055bd8b16a748545aebc3856ac89109da。
- 人工复核发现 q02“公司”编码误为gongshi；n02“权利”九键误为582654。v2分别改为gongsi、782654（q02分类改为everyday），在v2推理前发布 `452a7660c6a6f40663841a7b53692f3b90ba2d51`，SHA 3f250fe41d33149e734ff75be25c56eae86e763bb855be8a9e90389b9f1e9e93。原v1两项缺失不能归因于词库。
- `host-first-failure.json` 保留首次system-temp根目录下core open失败；仅改测试临时目录到仓库target后完成。尚未隔离该路径失败的唯一根因，未改生产存储保护。
- `host-v1-first-completed.json.gz` 和 `host-v1-final.json.gz` 保留v1的完整原报告；`host-v2-ort124.json.gz` / `host-v2-ort128.json.gz`为v2最终runner的原报告，gzip解压恢复原JSON字节，原字节SHA在compressed-reports.json。
- summary.json便于直接阅读，来自最终两个v2报告，仅去除逐用例观测和资源明细。build-source-identity.json记录实际core/probe/runner摘要；core生产目录相对ce26c14未变。manifest.json校验归档文件。
- 134项workspace、fmt、strict Clippy已通过；真实native15组、JNI/JVM36项和独立raw-ORT oracle通过。新增7项runner单测、原4项聚合回归通过。源码、主机、设备证据分层，JVM不是手机验收。

## CI与父分支失败

初始工具源码 a6fa632 的 [candidate run42387885136](https://github.com/ForceMind/ZapIME/actions/runs/42387885136) host-model job128449052304已成功；它执行的是v1/初版runner，不能代填最终v2或新增运行库参数。新的最终源码CI另行更新，不将进行中、取消或旧结果改写为通过。

父分支ce26的candidate、共享核心、VoiceKey、Windows、离线语音、同步服务共6条workflow成功；完整Android [run42366817647](https://github.com/ForceMind/ZapIME/actions/runs/42366817647)整体FAIL：API28/36及三条VoiceKey作业成功，API35普通输入和本机备份SAF失败。

已下载原artifact13572319233并复核摘要，见parent-api35-artifact.json。启动失败现场明确显示Pixel Launcher isn't responding，last-anr记录其无焦点窗口输入分发超时，test host已resume；这支持“被系统Launcher ANR窗口遮挡”的解释，不是产品ANR结论。独立备份报告5项PASS、第6项system SAF export失败且cleanup_completed=true；没有足够证据认定与Launcher是同一根因。原始安全选段已归档，未重新公开恢复码页面/截图。不能把清理成功当输入或SAF通过，后续原门禁必须实际复验。

本切片不改产品默认模型、设置、输入/隐私/语音路径，不操作用户设备、服务器或DNS。待完成：更广语料、空上下文回退的独立切片与系统IME验收、ARM/S25质量/延迟/能耗、其他宿主和正式签名。
