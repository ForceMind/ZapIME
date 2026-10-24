# 本地中英翻译探针：可运行与可用于学习是两项验收

2026-10-24实际完成独立Linux x86-64 CPU探针。已验证固定模型转换、合成短句推理和进程级网络受限复跑；**没有接入输入法，也不足以选择当前流水线为默认英语学习输出**。

## 实际结果

| 合成中文 | 真实译文，未筛掉问题样本 |
| --- | --- |
| 明天见。 | See you tomorrow. |
| 我正在学习英语。 | I'm learning English. |
| 谢谢你的帮助。 | Thank you for your help. |
| 我一会儿就到。 | I'll be there in a minute. |
| 今天有点堵车。 | A little traffic today. |
| 我已经在路上了。 | I'm on my way. |
| 我没听清楚，你能再说一遍吗？ | I'm not listening. Could you repeat that? |
| 我们改天再约吧。 | We'll see you later. |
| 这个方案还需要进一步讨论。 | This option requires further discussion. |
| 先这样吧。 | Let's do this. |

“没听清楚”被变成“不在听”是明显语义偏移；“改天再约”的安排含义弱化，“先这样吧”存在语境与语用歧义。片段式表达也需结合语境判断，不强行指定唯一英语答案。此探针只用了包内SentencePiece，未复现外部Moses预处理，因此结果是**当前探针配置**的定性观察，不能当作完整生产流水线或模型总体准确率结论。没有打准确率分。

后续先核实完整预处理、解码与更广泛日常表达，再比较其他许可清楚的模型。当前配置不作为默认教学输出，不因前三句好看就直接接键盘。

## 资产与许可

- 原始包为发布者模型卡直接链接的`zho-eng/opus-2020-07-17.zip`，288,654,138字节；SHA256为`9749101f963f552a270ff4de0c8b69c88f51c358d6b4cc87760aff2cffcf4067`。这是HTTPS取回后的本次观测锁定值，不冒充独立审计的发布者签名/校验和。
- ZIP内部LICENSE明确为CC-BY-4.0，已直接读取，原文与README保留在`third_party/notices/opus-mt-zho-eng-20200717-*`。不能把训练数据仓库根NC许可直接套到此包，也不能把此前只核对HF转换版的声明当包内许可检查。
- 使用CT2 4.8.3、NumPy 2.5.4、SentencePiece 0.2.2、PyYAML 6.0.3，未安装Torch/CUDA依赖，执行设备为CPU。仅保留源脚本与证据；权重、wheel、venv和缓存留在忽略的target。
- `model.bin`为82,713,357字节；加词表、分词、许可和说明的推理资产共85,648,224字节，不含运行库。磁盘文件大小不代表峰值内存，RSS与移动端联合语音负载尚未测量。
- 研究工具依赖不等于客户端二进制发行审计完成；引入任何客户端包前仍须检查全部运行库、算子/ABI、模型许可与署名。

官方模型卡：https://huggingface.co/Helsinki-NLP/opus-mt-zh-en 。原始包与随包声明是本次资产依据。

## 执行边界

单个inter/intra CPU线程，greedy beam=1，实际compute type为`int8_float32`，最多64解码token。原始转换耗时约1.10秒；十句原探针单次推理含分词约35.6–79.2ms，模型构造约112ms。文件系统已被转换暖缓存，数字不含Python进程/import启动，不是统计基准、手机速度或冷启动承诺。

网络受限复跑使用新进程，在导入ML库前叠加`no_new_privs`和libseccomp过滤器，拒绝记录的socket/connect/send/receive调用；实际IPv4/IPv6 socket创建均EPERM。十条结果与原网络可用基线一致。容器拒绝了无提权的network namespace尝试，未升级权限。这是特定进程的调用限制，不是手机飞行模式或通用安全沙箱证明。

没有验证Android/iOS/macOS/Windows推理、键盘英语条、续词联想、用户收藏、真机内存/耗电/按键延迟，也没有运行时网络翻译或R2操作。英语伴学独立策略已合并PR #44，但尚无生产调用者。

## 复现

限定Linux x86-64、Python3.12、curl与libseccomp，约1GB工作空间；从仓库根目录执行：

```sh
bash scripts/translation_probe/reproduce.sh
```

这是显式开发实验，会下载官方固定模型和hash锁定的PyPI wheels；不是安装器或键盘自动下载。默认工作目录为`target/translation-probe`，已有转换输出会拒绝覆盖。可用绝对路径`ZAPIME_TRANSLATION_WORKDIR`指定新的独立工作目录。

已有模型时只重跑网络受限的十句：

```sh
target/translation-probe/venv/bin/python scripts/translation_probe/probe.py infer --daily --offline
```

脚本不执行包内preprocess/postprocess shell文件。固定归档SHA/大小/许可核验、安全成员提取和转换已在原探针分别实际运行；仓库入口又实际重跑了归档核验/提取、hash约束wheel解析、网络受限十句推理，结果与原记录一致。`py_compile`和`bash -n`通过。**整条复现shell没有再次从空目录完整下载执行**，不冒充该额外测试。

证据见[本轮原始目录](evidence/english-learning-cpu-20261024/README.md)，包括完整十句、原先三句、来源/许可/模型和wheel SHA、原探针脚本文本及仓库入口复验。没有提交模型或二进制。

后续实际补齐预处理和beam对照、从空目录重跑原入口与未筛选19句输出见[第二轮报告](ENGLISH_TRANSLATION_PREPROCESSING.md)。原始三句/十句失败证据保持不覆盖，当前流水线仍不作为默认教学模型。
