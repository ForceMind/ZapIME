# OPUS 2021 中译英 CPU 对照

2026-10-24完成一个独立、可复查的本地模型实验：只测试官方 `zho-eng/opus-2021-02-18.zip`，没有继续扩大模型清单。相较已保存的2020流水线，“没听清楚”等例子有改善；**仍有语用、主语和时间信息问题，未选为英语伴学默认模型。** 没有接入键盘、用户正文、收藏或云服务。

## 固定来源和真实转换

模型来自[Helsinki-NLP官方该方向索引](https://github.com/Helsinki-NLP/Tatoeba-Challenge/blob/d20a5002da37b291fe54a6a5b0a81b6bf2958dec/models/zho-eng/README.md)链接的[原始ZIP](https://object.pouta.csc.fi/Tatoeba-MT-models/zho-eng/opus-2021-02-18.zip)。实下载288,551,434字节，整包观察SHA256为：

`cdef260995db149d7d519d930bd30c9515e56848ec6ea58388c5f5d647239e69`

这是本次HTTPS取回后固定的观察值，没有独立发布者校验单或签名核验。不是假设存在的“tc-big中译英”；这个包仍是普通transformer。

准备脚本只从ZIP固定白名单生成manifest，并在新目录验证。十项模型、词表、SPM、README、许可和配置逐项检查大小/SHA，拒绝重复成员、路径穿越及符号链接目的文件；不执行归档shell。NPZ以`allow_pickle=False`读取，配置及decoder使用安全YAML解析；词表计数使用转换器自身的Marian词表parser，避免普通YAML解析丢失特殊键。实际为6层encoder、6层decoder、512维、共享65000词表项，源/目标SPM各32000项。

本次复用既有Linux x86-64/Python3.12环境：CTranslate2 4.8.3、NumPy 2.5.4、SentencePiece 0.2.2、PyYAML 6.0.3。真实执行官方`OpusMTConverter`，无需Torch。转换后`model.bin`为82,713,357字节，SHA为`d0deac7c66d2daa430dc081d6ef08e4700eae54b11918fe438917fa4e1b7ef83`；模型、CT2配置/词表、两份SPM及模型README/许可合计85,647,125字节，**不含运行库，不是RSS**。

原始权重/包内材料仍为CC-BY-4.0，作者来源为Helsinki-NLP/OPUS-MT；包内LICENSE与已有[完整许可原文](../third_party/notices/opus-mt-zho-eng-20200717-CC-BY-4.0.txt)字节相同，SHA为`945fbcb37deca05bccd7c3ca0e261679f1f5373515504fdbad68f6f3cf6f49b9`。2021权重不能写成Apache-2.0；CT2运行库的MIT许可单独适用。权重、ZIP、NPZ、SPM、运行库均留在忽略的target，不随此次源码或产品安装包分发。

## 实际预处理与19句结果

该包的预处理是内嵌sed标点替换、Perl Unicode类别C替换、ASCII空格折叠/裁剪，再做包内SPM；与2020包的helper调用路径不同。本适配器只表达审查后的固定行为，调用一个固定Perl类别C程序，不执行归档shell，不继承PERL5OPT等设置。保留原包的异常映射“全角１变引号”和类别C会拆开合法ZWJ表情的限制，没有悄悄纠正它们；这不表示产品应接受控制字符或照搬这些规范化规则。

beam 1和原decoder给出的beam 6各跑一个网络可用进程和一个libseccomp受限进程，共四个新进程，每个都保留相同19条合成输入。两组的源句、规范化结果、全部译文及模型身份分别一致。与此前2020完整预处理对照的19条源句、规范化结果也一致；模型包、权重、词表及SPM不同，不能把变化只归因于某一个部件。

下表为真实输出；所有例子及坏例均在[证据目录](evidence/english-learning-opus2021-20261024/README.md)，没有用手写英文替换模型结果。

| 中文 | 2020完整预处理、beam 6 | 2021、beam 1 | 2021、beam 6 |
| --- | --- | --- | --- |
| 我没听清楚，你能再说一遍吗？ | I'm not listening. Could you repeat that? | I didn't hear you. Can you say that again? | I didn't hear you. Can you say that again? |
| 不是不想去，是今天没时间。 | I don't want to go. I don't have time for this. | Not that I don't want to go. I don't have time for this. | It's not that I don't want to, it's that we don't have time for today. |
| 我们改天再约吧。 | We'll meet again sometime. | Let's meet again. | Let's make another appointment. |
| 先这样吧。 | Let's get this over with. | Let's do this. | Let's do this. |

“没听清楚”的偏移得到改善，双重否定也不再直接倒转；但beam 1丢失“今天”，beam 6凭空增加“we”，并有生硬的“for today”。“改天再约”仍可能丢掉时间含义；“先这样吧”没有解决语用歧义。beam 6的“今天有点堵车”输出`There's a traffic jam today.`也没有保留“有点”的程度。不以这19句给出准确率、教学质量通过或移动端可用结论。

## 资源与限制

真实执行为单个inter/intra CPU线程、INT8转换、实际`int8_float32`。显式`length_penalty=1`、最多64个输出token；beam 6并不等于复现原生Marian全部搜索/停止规则或数值结果。

四个Python进程的Linux峰值RSS为171,592–179,952KiB（约168–176MiB），包括导入、模型及19句，不包括独立Perl子进程。整进程约2.56–3.11秒。beam 1各句观测约45–200ms、beam 6约72–324ms，包含预处理/SPM；这是单次、已被校验和前序操作暖过文件缓存的观测，没有统计分位数、冷启动、耗电、按键竞争、手机或iOS扩展验收。

网络受限进程在ML导入前安装既有libseccomp策略，IPv4/IPv6 socket创建均实际EPERM，后续线程及固定Perl子进程继承限制。它仅限制特定网络系统调用，不是完整恶意代码沙箱、所有继承FD/io_uring路径的证明或设备飞行模式测试。推理阶段不下载缺失资源。

## 复现与本次检查

已有固定`target/translation-probe/venv`时，从仓库根目录运行：

```sh
bash scripts/translation_probe_2021/reproduce.sh
```

它显式准备唯一2021模型、运行6项规范化边界测试及四进程对照；拒绝覆盖已有`ct2-int8`。没有环境时，先按既有[CPU探针环境说明](ENGLISH_TRANSLATION_PROBE.md)建立Linux x86-64/Python3.12的固定四wheel环境。可以用绝对路径`ZAPIME_TRANSLATION_2021_WORKDIR`选择另一个模型实验目录；venv仍复用原路径。

已有准备结果的轻量重新核验与单进程重跑分别为：

```sh
target/translation-probe/venv/bin/python scripts/translation_probe_2021/prepare.py --verify-existing
target/translation-probe/venv/bin/python scripts/translation_probe_2021/run.py --beam-size 6 --offline
```

实际完成：完整ZIP下载/整包SHA、固定成员/许可/decoder/NPZ/SPM校验、真实转换、四进程对照、6项规范化测试、Python语法和shell语法检查；最终准备脚本在新目录用同一预置已校验ZIP验证，转换结果与真实推理输入逐字节身份一致。后续仅补平台guard和准备manifest范围，最终轻量校验已跑，没有重复四进程推理。完整`reproduce.sh`没有另从完全空目录重跑，不将各步骤成功冒充额外的一键全程记录。

下一步仍是扩大可复查的语义与移动资源验收，再决定产品模型和运行库；本轮不增加无实际能力的客户端开关，也不标记英语伴学或P0四端交付完成。

根代理独立复核：相同6项规范化回归、Python/bash语法、最终prepare --verify-existing均通过；另以最终源码重跑一次beam6受限19句，全部原句/规范化/译文及模型身份与原始证据一致。该额外复跑不并入上述四进程资源观测。
