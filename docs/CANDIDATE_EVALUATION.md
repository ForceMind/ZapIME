# 候选增强的配对评测

新增 `scripts/candidate_evaluation.py` 为后续排序质量与延迟采样提供统一统计入口。它只统计外部采样，不调用模型、不读取输入记录、不联网。当前尚未接到 Android 采样器，也没有新增手机性能或语料质量结论。

先固定独立合成用例和可接受候选，再运行基础排序与增强排序。每个唯一输入只占一行；冷启动、热启动、不同设备、模型和运行库分别保存观测文件，不把重复计时当新增语言样本。候选 ID 必须是两个顺序共用的不透明标识；比较最终可见候选页，保留个人规则和非增强候选的实际位置。不得为提高结果先删掉目标缺失的输入。

输入 JSON 示例（这些数字只是统计器的演示输入，不是实测）：

```json
{"schema_version":1,"synthetic":true,"cases":[
  {"id":"fixture-001","expected":["target"],
   "baseline":["other","target"],"ranked":["target","other"],"latency_ms":12}
]}
```

```bash
python3 scripts/candidate_evaluation.py target/observations.json --report target/evaluation.json
python3 -m unittest discover -s scripts -p 'test_candidate_evaluation.py' -v
```

报告包含固定分母的首选率、前三命中率、MRR、候选缺失数、排序改善/退步、首选挽回/损害及 nearest-rank P50/P95。缺失目标按零分计入全部分母。多个可接受答案取最先出现的名次；重复 ID、重复候选、改变候选集合、非法计时和非合成声明均拒绝。4 MiB 输入、10,000 用例及每页 256 候选为统计工具边界，不修改生产增强页上限。

默认报告不输出用例 ID、候选或上下文，仅输出聚合值和输入 SHA256；错误不回显输入。输出采用 create-only，防止误覆盖旧报告。`synthetic=true` 是调用者声明，工具不能识别伪装成合成数据的真实输入，禁止向公开 CI 提交真实用户文本。此工具也不能检测语义重复的不同 ID；用例来源、固定版本和去重需独立审查。

后续实际采样须附提交/二进制/模型/词库摘要、设备/系统/线程配置、计时边界、冷暖状态及失败数量。此统计器不自动生成这些证明；采样失败不能从输入集静默移除。完整候选 worker 的取消、队列等待、绘制和上屏延迟必须单列，纯推理耗时不能替代端到端体验。真实语料、S25 RSS/能耗、25M 对照和其他宿主仍未验收。
