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

## 真实词库与模型的主机评测入口

`integrations/candidate/tests/quality_evaluation.py` 已接入生产词库、release 核心及真实 native 8M 推理。语料 v1 在首次推理前提交 `75ae851`，34 条用例覆盖同音词、日常词、单字、九键和补全/部分输入边界；每个输入使用新的临时资料库，阻止前一个用例的学习污染下一个。完整最终可见候选列表参与统计，只有生产契约允许的既有槽位进入重排，非增强项的位置和内容必须保留。

v1 的人工拼写审查发现两处编码错误：q02“公司”应为 gongsi，n02“权利”九键应为 782654。它们不是词库缺失证据。v2 只纠正这两处及相应分类，在再次推理前单独冻结于 `452a766`，保留 v1 原字节及全部结果，不混合两个版本计算收益。语料 SHA 锁定在 runner；不得按模型输出来改可接受答案或删除退步用例。小型人为用例存在选择偏差，不是用户分布上的准确率测试。

```bash
# 先按 integrations/candidate/README.md 准备固定模型、头文件和 probe。
python3 scripts/fetch_resources.py
cargo build --release --locked -p zapime-core
# 使用固定 requirements.txt 的 Python 虚拟环境：
python integrations/candidate/tests/quality_evaluation.py --report target/candidate-host/quality-report.json
# 可复现旧版，不覆盖新版报告：
python integrations/candidate/tests/quality_evaluation.py --corpus-version v1 --report target/candidate-host/quality-v1.json
```

`--runtime /absolute/path/libonnxruntime.so` 可在主机对照官方 Linux ORT 1.28.2；报告通过 OrtGetApiBase 读取实际版本并记录库 SHA，不拿 Python 包版本代填。固定官方归档校验值见 integrations/candidate/README.md。本参数只在开发主机执行指定库，不能用于 Android 注入或改变产品运行库。

报告分别提供空上下文和冻结上下文的配对统计；同样的 34 个用例不记为 68 个独立语言样本。目标候选缺失仍记零分。零基候选索引均值只是选词成本的代理，缺失数独立列出，不当作实际点击数。每模式 32 个可评分用例各执行一次清缓存评分和一次暖缓存评分，共 64 次 score；其余 2 个保留基础排序。只在实际评分子集统计推理 P50/P95，避免无推理用例的零耗时稀释延迟。

进程加载耗时包含 spawn/库/模型初始化；评分耗时包含 native stdin/stdout 往返，不含核心打开、逐键、80ms 防抖、worker 排队、界面绘制和上屏。`/proc` VmRSS/VmHWM/Threads 仅对应独立 scorer 进程，不能推算整个 App、手机内存或能耗。超时/协议错误拒绝成功，保留已完成用例和失败 ID；统计聚合不输出正文，明细只含冻结用例 ID 与不透明候选 ID。

[实测报告与原始失败](evidence/candidate-quality-20261107/README.md)已保存。生产代码和默认开关在本切片未改动。空上下文退步在两个运行库版本均出现，需要单独验证保守回退策略；不要仅凭这 34 条人为语料更换默认模型或宣称达到手机质量目标。
