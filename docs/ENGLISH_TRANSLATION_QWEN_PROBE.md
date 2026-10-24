# Qwen小模型本地翻译探针：固定配置未通过教学采用判断

2026-10-24实际完成第二种模型的独立CPU实验：官方`Qwen3-0.6B-GGUF`的Q8_0文件，通过固定官方`llama.cpp` CPU包执行同一19条合成中文。英文、中文两套固定提示分别完成网络可用与系统调用受限复跑，共76个原生子进程。

**该温度0、禁思考、两套提示配置有明显语法错误和添加日期，未选作默认英语伴学输出。** Qwen官方为非思考模式建议采样参数，本次受控贪心并非那个推荐配置；不能据此断言该模型所有设置或其它模型的质量。没有接入APK、手机或iOS键盘，也没有逐句调提示来替换坏例。

## 固定资产与实际来源

| 资产 | 固定版本及实测校验 |
| --- | --- |
| 官方GGUF | `Qwen/Qwen3-0.6B-GGUF`，revision `23749fefcc72300e3a2ad315e1317431b06b590a` |
| 模型文件 | `Qwen3-0.6B-Q8_0.gguf`，639,446,688字节；SHA256 `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`，与发布者LFS元数据一致 |
| 模型许可 | 直接读取上述精确revision的完整LICENSE，为Apache-2.0；11544字节，SHA256 `5de36594c10839788a8c589443a8ef9d8b8d17c65a1b5807206ae037fc36c6bd`，证据保留原文 |
| 官方运行包 | `llama-b11669-bin-ubuntu-x64.tar.gz`，17,851,611字节；SHA256 `cf07c1c112d709bd67e522017471de57ed0ec8bea031d732c5422351c07f65ac`，与GitHub发布资产digest一致 |
| 对应源码 | `620d2225cc436ab0b001e3200e590e3b286ef9ab`；官方v0.7.0指向b11669；实际二进制报告`0.7.0-dev (build 11669, commit 620d2225c)`、GNU11.4.0、Linux x86-64 |
| 模板依据 | 官方`Qwen/Qwen3-0.6B` revision `c1899de289a04d12100db370d81485cdf75e47ca`中的`tokenizer_config.json`；完整模板及SHA均保留 |

模型、runtime、完整许可、官方说明与审阅的`completion.cpp`/`arg.cpp`均固定大小/SHA。只取一个639MB模型及约18MB CPU运行包，没有取其它大模型或CUDA包。推理前再次核验资产，并把实际解压的每个常规文件、symlink与**已校验官方tar成员**逐项比对，拒绝额外文件，不能只核对未改的压缩包后执行已变化的库。

运行库主许可为MIT，原包LICENSE与固定源码相同；这是发布者资产与源码版本关联，不是独立复现其构建。包内还带RPC后端，动态依赖涉及系统OpenSSL、libgomp、libstdc++等。**“CPU执行”不能写成“库没有网络能力”或“已完成客户端所有依赖许可审计”。** 二进制、权重和下载缓存留在忽略的target；Git中只有本项目脚本、完整主许可和文本证据。

## 提示、禁思考与输出判据

两个system提示在跑完整句集前固定：

```text
Translate the Chinese text into English. Output only the translation, without explanations. Preserve negation and intent. /no_think
```

```text
你是一名专业的中英翻译。请把用户输入的中文翻译成自然、准确的英文，保留原意和否定关系。只输出译文，不要解释。/no_think
```

本次没有调用Transformers `apply_chat_template`，也没有假称给CLI传了一个不存在的`enable_thinking`参数。脚本手工渲染固定官方模板中“两个消息、无tools、add_generation_prompt=True、enable_thinking=False”的分支，预填其完整assistant后缀：

```text
<|im_start|>assistant
<think>

</think>

```

每条记录保留完整ChatML提示、中文原句、提示SHA以及官方完整模板。没有在输出后删除思考段来冒充禁思考。当前只处理固定合成句，尚无任意用户文本中的特殊token/指令注入验收；这不是可直接接用户文本的产品协议。

真实执行的是`llama-completion`非交互入口，明确禁止再次套用会话模板，全部实际flags在各条原始记录中。关键参数为：

```text
--no-conversation --no-display-prompt --simple-io --color off
--verbosity 0 --offline --no-escape --no-context-shift --no-warmup
-ngl 0 -t 1 -tb 1 -c 1024 -n 128 --temp 0
```

每句都用新原生进程、本地模型文件和完整prompt文件。受控子进程环境不继承`LLAMA_ARG_*`、代理或`LD_PRELOAD`；系统及本次专用XDG配置路径的`config.ini`存在就拒绝。没有改用户的HOME或设置目录。

该固定源码只在非交互EOG分支打印` [end of text]`。脚本保留**完整raw stdout及stderr**，只剥掉经源码与实际运行确认的精确CLI结尾包络`" [end of text]\n\n\n"`，不按泛化正则从日志挑“好看的译文”。非零退出、超时、缺少精确EOG包络、空输出、思考/角色控制字样均拒绝结构通过；完整正文仍保留。所有76次实际退出为0，EOG包络均存在，没有本轮128-token截断；这只证明执行与结束形状，不等于语义合格。

`--verbosity 0`隐藏了性能日志，本次没有从混合输出捏造token/s。主译文字段仅去除显示首尾空白，另有未裁剪正文；引用/解释等模型内容不会被悄悄删除。

## 真实结果，包含坏例

| 合成中文 | 英文system实际输出 | 中文system实际输出 |
| --- | --- | --- |
| 明天见。 | Tomorrow see. | Tomorrow see. |
| 我正在学习英语。 | I am learning English. | I am learning English. |
| 谢谢你的帮助。 | Thank you for your help. | Thank you for your help. |
| 今天有点堵车。 | Today is a bit stuck. | Today is a bit stuck. |
| 我已经在路上了。 | I have been on the road. | I have been on the road. |
| 我没听清楚，你能再说一遍吗？ | I don't understand. Could you repeat it? | I don't understand. Could you repeat it? |
| 我们改天再约吧。 | We'll meet again tomorrow. | We'll meet again tomorrow. |
| 不是不想去，是今天没时间。 | Not wanting to go, but today was not available. | It's not that you don't want to go, but you didn't have time. |

“改天”被加成明确“明天”是实质性偏移；“明天见”和“堵车”有明显不适合学习的表达。“没听清楚”变为“不理解”，听觉含义弱化。没有把更流畅的提示或成功的三句代替完整坏例，也没有用单一参考答案打准确率分。

四份分组JSON保留全部19句，包括其它日常表达、全角时间/数字/标点、空白与两个控制/格式字符研究样例。后两者只用于对照，不代表产品隐私/文本门禁会接受它们。两个system各19条的源句、完整提示、raw stdout、raw stderr、显示正文、退出/EOG/结构结果，在网络可用与受限轮之间逐项相同；资产身份也相同。

本次质量结论只限定这套固定配置。下一轮如比较官方推荐采样，需预先固定参数/种子、仍用完整句集保存全部结果；本轮没有运行那项额外实验，也不宣称贪心结果代表模型总体质量。

## 资源与网络限制

Linux x86-64、Python3.12、单CPU推理/批线程、1024上下文、最多128个新token、Q8_0。76个新原生进程的峰值RSS为802,720–804,344KiB（约784–786MiB）。每个新Python worker只启动一个原生child，以Linux`RUSAGE_CHILDREN.ru_maxrss`记录该child，包含动态加载、模型映射、上下文和生成，不含父Python，也不是模型独占内存。

每句整个原生进程观测约1.97–7.90秒，含启动、加载、prompt与生成；文件缓存已被SHA验证、先行检查和前序句子暖过。没有持续驻留模型的延迟统计、冷缓存测量、能耗、并行语音或按键竞争测试。不能用这组云容器数据声称手机很快，更不能据磁盘大小或iPhone示例App判断iOS键盘扩展可承受该内存。

“网络可用”基线指OS没有额外过滤；native `--offline`仍一直启用，不等于调用云翻译。受限轮每个新worker在spawn原生程序前叠加`no_new_privs`与libseccomp过滤，子进程继承；38个worker实际IPv4/IPv6 socket创建均EPERM。限制范围与[上一探针](ENGLISH_TRANSLATION_PROBE.md)一致：特定socket/connect/send/receive系统调用，不是完整沙箱、所有继承FD/io_uring路径封锁或手机飞行模式。没有启动HTTP翻译服务、接账号、同步或R2。

## 其它候选的只读核查

| 候选 | 已核实事实 | 本轮决定 |
| --- | --- | --- |
| Helsinki `opus-mt_tiny_zho-eng`（2026） | 官方25.4M参数，有20,144,264字节LiteRT与68,715,149字节Marian文件；模型卡标Apache-2.0，但明确是由本项目已测的2020 OPUS教师蒸馏 | 可作资源对照，不能因“新上传/更小”假定解决否定错误；未下载 |
| 腾讯HY-MT1.5 1.8B的2026低比特版 | 官方有440MB/574MB端侧路线；实际权重License.txt是Tencent HY社区许可，含地域及分发/用途条件，不是压缩代码的Apache许可 | 不作为本项目无地域限制的默认分发模型；未下载 |
| Qwen3-0.6B-GGUF | 精确官方资产、Apache主许可与成熟本地CPU入口可核实，639MB低于本次700MB实验上限 | 完成本报告的真实配置探针，仍未选为默认教学输出 |

本轮没有下载数GB模型。NLLB的非商用权重不进入默认候选。模型卡的参数、语言标签或厂商benchmark都不是本项目教学质量证据。

## 复现与保存边界

要求Linux x86-64、Python3.12、HTTPS下载能力和`libseccomp.so.2`，约1GB额外空间。从仓库根：

```sh
bash scripts/qwen_translation_probe/reproduce.sh
```

这是显式开发实验，会下载固定公开模型及runtime，不是客户端安装器或自动模型包。默认工作目录`target/qwen-translation-probe`；可用绝对路径`ZAPIME_QWEN_PROBE_WORKDIR`另选独立目录。下载只在`prepare.py`，推理和比较入口没有缺失时下载/联网回退。已有资产重跑可分步执行：

```sh
python3 scripts/qwen_translation_probe/prepare.py
python3 scripts/qwen_translation_probe/test_probe.py
python3 scripts/qwen_translation_probe/run.py
python3 scripts/qwen_translation_probe/run.py --offline
python3 scripts/qwen_translation_probe/compare.py
```

本次实际执行：显式curl下载模型/runtime并核对发布者SHA、prepare校验/解压、在线38条、受限38条、完整配对比较、6项结构/错误/模板/损坏资产回归、Python语法检查及shell语法检查。**整条新reproduce.sh未再次从空目录完整执行**，不要冒充这项额外验证。完整证据在[本轮目录](evidence/english-learning-qwen-20261024/README.md)，两个大结果按提示分成四个可读JSON，原stdout字符串不改写，并记完整源文件SHA。

后续模型选择和生产provider/英语条仍须单独实现及验收，宿主事件与生命周期进度以主STATUS为准。当前不会以测试英文填充UI；本轮实验也不改变P0四端输入和可恢复数据的交付要求。

官方来源（2026-10-24读取）：

- [Qwen精确GGUF版本](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/23749fefcc72300e3a2ad315e1317431b06b590a)、[LICENSE](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/blob/23749fefcc72300e3a2ad315e1317431b06b590a/LICENSE)、[官方模板](https://huggingface.co/Qwen/Qwen3-0.6B/blob/c1899de289a04d12100db370d81485cdf75e47ca/tokenizer_config.json)、[本地运行说明](https://qwen.readthedocs.io/en/latest/run_locally/llama.cpp.html)。
- [llama.cpp v0.7.0](https://github.com/ggml-org/llama.cpp/releases/tag/v0.7.0)、[b11669资产](https://github.com/ggml-org/llama.cpp/releases/tag/b11669)、[固定completion源码](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/tools/completion/completion.cpp)、[固定参数/配置源码](https://github.com/ggml-org/llama.cpp/blob/620d2225cc436ab0b001e3200e590e3b286ef9ab/common/arg.cpp)。
- [Helsinki tiny官方模型卡](https://huggingface.co/Helsinki-NLP/opus-mt_tiny_zho-eng)、[文件](https://huggingface.co/Helsinki-NLP/opus-mt_tiny_zho-eng/tree/4ee3a9342aae4ca6fbae82c930b5dccff5dd68dd)。
- [腾讯低比特官方卡](https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit)、[该GGUF版本权重许可](https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit-GGUF/blob/3d09c4ce7dc00aa7182cb348a760c7d8f6983224/License.txt)。
