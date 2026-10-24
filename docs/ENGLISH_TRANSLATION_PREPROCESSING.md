# 本地翻译预处理与搜索对照

2026-10-24完成固定OPUS-MT中英模型的第二轮独立Linux CPU实验。补上归档实际启用的预处理，并分别对照greedy与beam 6。**有局部改善，也有否定含义倒转；仍不能把此流水线选为英语伴学默认输出。** 没有接入APK、用户输入或云服务。

## 补齐了什么

上一轮只用了包内SentencePiece。本次先实际从空`target/translation-probe`运行原来的`reproduce.sh`：重新下载并按SHA核验四个wheel与固定288,654,138字节模型ZIP，转换INT8，再完成三句/十句的网络可用及受限推理。全部命令成功退出，文件证据已保存；原始终端完整转录没有另存，不冒充有额外日志。

归档自身`preprocess.sh`的启用路径为：

1. Moses `replace-unicode-punctuation.perl`；
2. Moses `remove-non-printing-char.perl`，把Unicode类别C字符换为空格；
3. 仅折叠ASCII空格并裁剪行首尾ASCII空格；
4. 固定`source.spm`分词。

归档的`normalize-punctuation.perl -l ...`是被注释的另一行，**本次没有开启它**。没有执行归档shell或其主机名/路径分支。新适配器只调用两份审阅过、逐项校验SHA的独立Perl helper，再按原sed行为处理ASCII空格。不会用`str.split()`吞掉其它Unicode空白。

helper固定到Moses仓库提交`08e782040189e0abb3b43a4ec9245e6179e39eca`，各文件SHA、大小和来源见证据manifest；它不是2020年原训练机器安装目录的完整环境锁。Perl子进程不继承`PERL5OPT`、`PERL5LIB`及`PERL_UNICODE`，本次为Perl v5.38.2。原脚本先按UTF-8字节替换标点，再以Unicode I/O处理类别C，不能随意合并成另一套正则。

`decoder.yml`给出的beam为6、normalize为1。原探针使用greedy 1；CT2转换器不会自动采用这些解码选项。本次作2×2对照，显式固定`length_penalty=1`，保留64-token上限和其余CT2默认值。**这不是原生Marian逐数值等价复现**：INT8、搜索/停止实现、UNK规则、最大长度、SentencePiece版本等仍有差异。

译文主字段仍使用`SentencePiece.decode()`，以便把预处理和beam的影响分开。另记`archive_sed_translation_untrimmed`，直接按归档postprocess拼接token并替换`▁`；该路径保留dummy-prefix首空格，对`<unk>`等也可能不同，不能称两个后处理逐字相同。

## 没有筛掉退化的结果

每种配置均跑相同19条合成文字；每种再分别跑独立的网络可用进程和libseccomp受限进程，最终记录所选的8个新推理进程。四组配对的源句、规范化结果、译文与模型文件身份全部相同；原10句greedy无预处理基线与前轮证据一致。

以下摘出有判断价值的变化，全部19句、四配置及两种网络状态均保留在[证据目录](evidence/english-learning-preprocessing-20261024/README.md)。表内为真实模型输出。

| 中文 | 无预处理、beam 1 | 完整预处理、beam 1 | 完整预处理、beam 6 |
| --- | --- | --- | --- |
| 今天有点堵车。 | A little traffic today. | There's a traffic jam today. | There's a traffic jam today. |
| 我没听清楚，你能再说一遍吗？ | I'm not listening. Could you repeat that? | I'm not listening. Could you repeat that? | I'm not listening. Could you repeat that? |
| 我们改天再约吧。 | We'll see you later. | We'll see you soon. | We'll meet again sometime. |
| 先这样吧。 | Let's do this. | Let's do this. | Let's get this over with. |
| 我没说你做错了。 | I'm not saying you did it wrong. | I didn't say you did it wrong. | I didn't say you did it wrong. |
| 不是不想去，是今天没时间。 | Not that I don't want to go, but that's that there's no time for today. | No, I don't want to go. I don't have time for this. | I don't want to go. I don't have time for this. |

“堵车”与“改天再约”有局部改善；“没听清楚”的语义偏移未解决。新增双重否定例子在预处理后把“并非不想去”的意图倒转；beam 6没有修好。“先这样吧”还可能增加不耐烦色彩。没有用一句唯一参考答案打准确率分，也没有因为局部更顺口而宣布模型适合教学。更大的beam并不自动更可靠。

其余对照包括全角时间/数字、引号、温度、全角句点、ASCII空格，以及两个控制/格式字符样例。后两者仅验证研究流水线的规范化行为，不表示产品应接受这些文字。7项独立回归还明确记录：Moses类别C处理会移除合法emoji的ZWJ并拆开组合表情；未来产品流水线需单独决定如何保护它，不能照搬后悄悄改变用户原文。

## 资源观测与边界

本轮仍是Linux x86-64、Python3.12、CT2 4.8.3、单个inter/intra CPU线程、`int8_float32`，模型与wheel锁未变。8个新Python推理进程的Linux`ru_maxrss`约172,796–180,232KiB（约169–176MiB），包括该进程导入、模型与全部19句，不包括独立Perl子进程；不是模型独占内存，更不是手机或iOS扩展的内存承诺。

整进程观测约2.05–2.94秒，包含Python导入、加载及19句。文件系统已被转换和前序实验暖缓存，单次且可能有宿主负载变化；没有冷启动、统计延迟、耗电、按键竞争或移动端验收。网络限制沿用前轮的特定进程系统调用过滤，IPv4/IPv6 socket实际EPERM，Perl子进程继承限制；这不是完整沙箱或设备飞行模式测试。

## 许可和复现

模型原包仍为CC-BY-4.0，原文及署名在已有`third_party/notices/opus-mt-zho-eng-20200717-*`。CT2是MIT；新增研究helper分别带LGPL-2.1-or-later文件头，下载时同时保留其官方COPYING。三者分开记录，不能把模型许可套给运行库。Moses helper、COPYING、权重、NPZ、wheel及venv均只在忽略的target，不随本次源码提交，也没有进入安装包。固定SHA是本次HTTPS取回后的观察锁定值，不是独立的发布者签名。

从空工作目录完整复现原基线，再显式准备helper和跑对照：

```sh
bash scripts/translation_probe/reproduce.sh
python3 scripts/translation_probe/prepare_preprocessing.py
python3 scripts/translation_probe/test_preprocessing.py
target/translation-probe/venv/bin/python scripts/translation_probe/compare_preprocessing.py
```

第一步拒绝覆盖已有`ct2-int8`；已有固定模型可直接从第二步开始。可通过绝对路径`ZAPIME_TRANSLATION_WORKDIR`使用另一个工作目录，其余命令保持同一变量。下载与推理分离：`prepare_preprocessing.py`才会联网取固定官方脚本；`probe.py`与`compare_preprocessing.py`不下载缺失资源。受限单配置重跑为：

```sh
target/translation-probe/venv/bin/python scripts/translation_probe/probe.py infer --contrast --preprocessing opus-simple --beam-size 6 --offline
```

实际通过：四个修改/新增Python脚本语法检查、7项预处理回归、完整原基线shell和8进程对照。此次没有产品功能开关或测试英文回填。后续应比较许可清楚的其它候选、扩大否定/时态/语用表达集，建立可复查的语义标准，再决定客户端运行库和英语条接入。

来源（2026-10-24核对）：

- [官方模型卡](https://huggingface.co/Helsinki-NLP/opus-mt-zh-en)，其中链接固定原包；包内preprocess/postprocess/decoder文件SHA见本轮manifest。
- [固定Moses标点脚本](https://github.com/moses-smt/mosesdecoder/blob/08e782040189e0abb3b43a4ec9245e6179e39eca/scripts/tokenizer/replace-unicode-punctuation.perl)、[控制字符脚本](https://github.com/moses-smt/mosesdecoder/blob/08e782040189e0abb3b43a4ec9245e6179e39eca/scripts/tokenizer/remove-non-printing-char.perl)、[COPYING](https://github.com/moses-smt/mosesdecoder/blob/08e782040189e0abb3b43a4ec9245e6179e39eca/COPYING)。
- [CT2 4.8.3 Translator参数](https://opennmt.net/CTranslate2/python/ctranslate2.Translator.html)、[Marian decoder参数](https://marian-nmt.github.io/docs/cmd/marian-decoder/)。
