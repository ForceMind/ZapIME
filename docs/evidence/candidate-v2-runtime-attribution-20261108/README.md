# 首批 v2 的 26/34 与 25/34：固定报告只读归因

结论：这两个数比较的是 **ORT1.28.2 与 ORT1.24.4**，不能称为同运行库的 26→25 退化。旧 ORT1.24.4、本次扩充 runner 的本地主机 ORT1.24.4、#89 CI ORT1.24.4 三份 v2 报告都是基础 17/34、空上下文 14/34、冻结上下文 25/34。旧官方 Linux ORT1.28.2 的冻结上下文是 26/34。本次扩充仅额外执行了 v3/1.28.2，没有新的 v2/1.28.2 报告。

所有比较仅解压并读取已有原 JSON，没有重新运行模型、修改语料或覆盖原报告。`audit.json` 给出每份 gzip 和原 JSON 的 bytes/SHA256、模型/词库/运行库/runner 身份，以及逐项比较结果。下表 SHA256 均针对 **解压后的原 JSON**：

| 固定报告 | ORT / 上下文首选 | 原 JSON SHA256 |
|---|---|---|
| [旧 v2/1.24.4](../candidate-quality-20261107/host-v2-ort124.json.gz) | 1.24.4 / 25/34 | `6b1166d2e947a52fda4b664fb43d21ce18ba637f9dc5da34854dbaf61d20df89` |
| [旧 v2/1.28.2](../candidate-quality-20261107/host-v2-ort128.json.gz) | 1.28.2 / 26/34 | `52e949001afdd054ea6ee4688a1c629a1d3711c056b9df7c4a9aa854b40e3dc3` |
| [扩充后本地 v2/1.24.4](https://github.com/ForceMind/ZapIME/blob/94b66f3e3ac9c0af92952427d0f3f1bfc27bb6a8/docs/evidence/candidate-quality-expansion-20261107/host/quality-v2-ort1244-regression.json.gz) | 1.24.4 / 25/34 | `67029ae4fc4007d4ca445c43ebd8613747d70dd429ed3d3a0f9a61e490760cca` |
| [#89 CI v2/1.24.4](../current-slices-20261107/candidate89-host/quality-report.json.gz) | 1.24.4 / 25/34 | `a7f848c293ae0d162c3f1805105af8e28ceee386f717312ed6d8be0a30b04f84` |

扩充本地报告属于尚未合并的固定#96源码，表中使用不可变提交链接；本页没有把该runner当作main已验产品。

三份 1.24.4 报告的两个模式各 34 条 observation，在仅排除 `latency_ms` 后，`id`、`expected`、完整 `baseline` 和完整 `ranked` 逐项相同。它们不只是首选聚合值恰好一致。冻结 v2 的 SHA `3f250fe41d33149e734ff75be25c56eae86e763bb855be8a9e90389b9f1e9e93`、34 条分母与模型/词库/ranking source 均不变。

旧两库使用相同 core、native probe 和 runner，只变更实际运行库身份：1.24.4 SHA `2d518e11c7767f9fdc4413bdbeb45acf30db504ff76a82fc19d9238a3f9f6c24`；1.28.2 SHA `088f24b1fc56714d3efaaeb3ac2ee486a5d7b50ccfbb3dd26fd1a612534a05fb`。冻结上下文的首选数量差 1，唯一来自用例 `q10`：目标不透明 ID `engine:2`，基础第 3，1.24.4 重排第 2，1.28.2 重排第 1。1.24.4 前两项为 `engine:0, engine:2`，1.28.2 为 `engine:2, engine:0`。这能归因观测到的名次差，不能据此认定唯一 ORT 数值或 kernel 根因。

完整候选顺序的其他差异也保留：冻结上下文模式 `q07/q11/q12/q15/n06` 的完整顺序不同但目标名次相同；空上下文模式 `n03/n06` 的完整顺序不同但目标名次相同。因此不能把两库描述为逐项完全相同。

旧 runner `7ff77a9` 的 SHA 为 `1192ae72bc4a164f11c2a3a8be848eade4ed45b989437fe78b9b47b42f9952f7`，扩充 runner `cd8a0bf` 为 `19930216eb40e12a713cda5c8b212a831f02a57bdba4cc6a1a4564898eb6ebd0`。源码差异新增 v3 校验、个人 fixture、逐项归因与零模型执行拒成功；`personal_edit` 仅在用例含 `personal` 字段时执行，冻结 v2 的 34 条均无该字段；v2 每模式仍实际评分 32 条/64 次调用。三份同运行库完整候选页相同，直接排除了本轮报告中由个人 fixture 或新聚合口径造成这 1 条差值的解释。

建议当前交接摘要同时注明历史两库口径与 #89 复跑的实际运行库，不把旧 26 改成 25，不为新 runner 代填尚未执行的 v2/1.28.2 结果。旧 v1 编码错误、core-open 首次失败、空上下文伤害及所有原结果继续保留。此小型合成集不代表真实用户准确率，也不代替 Android 系统 IME、ARM 或手机验收。
