# 离线语音整句评测

本工具用于回答固定测试音频上到底替换、漏掉或多出了多少字。它不改变Android语音模型、录音或上屏代码，也不把CPU/模拟器成功视为真人识别率。跟踪任务为[Issue #53](https://github.com/ForceMind/ZapIME/issues/53)。

## 固定数据与范围

`scripts/fixtures/voice-quality-v1.json`在本轮推理前固定12句合成文本，覆盖普通话、否定、中文金额/日期、短句、虚构姓名、中英混说、英文、两种语速和自我纠正。使用eSpeak的`cmn-latn-pinyin`或`en-us`，不是采集真人，也不是抽样得到的日常输入分布。合成器本身可能读错；无法据此区分所有声学模型问题与TTS发音问题。不得根据识别结果改参考文本、删除难句或把重复轮次算成新增样本。

生成manifest记录定义SHA、实际eSpeak版本和每个WAV的长度/SHA/样本数；评测前重新核对完整成员集合、16kHz/单声道/PCM16、60秒上限和实际PCM长度。manifest是本地一致性记录，不是可信签名或第三方认证。模型仍使用`voice-probe-lock.json`的固定四文件长度/SHA；模型、运行库、WAV和venv只在ignored target/CI产物，不进Git/APK/R2。

## 指标

CER = (替换S + 删除D + 插入I) / 参考字符数N。字符单位是Unicode码点，包括英文单词中的字母，不是UTF-16单元、字素或WER。算法使用有界滚动行；同距离对齐优先替换、删除、插入，故歧义对齐下S/D/I拆分可能与其它工具不同，总编辑距离相同。

每句同时保留两种口径：

| 口径 | 固定处理 |
| --- | --- |
| `verbatim` | 精确码点，包括大小写、标点和空格 |
| `speech-v1` | NFKC、转小写、删除Unicode P类标点和空白；符号、阿拉伯/中文数字差异、否定和简繁差异保留 |

规范化只用于评分，不改变模型输出、输入法正文或参考原文。空识别按整句删除计错；空参考分母没有定义，CER为null，不伪造0。本固定合成集拒绝空参考；插入过多可让CER超过100%，不截断，也不将`100%-CER`称为用户准确率。

每轮/每场景使用各句S/D/I/N之和计算加权CER，不平均每句百分比。三轮复用完全相同的12条音频，各轮分开显示；不会把它称为36条独立测试语音。英文和中英混说仍为字符指标，不能冒充英语词错率或跨公开榜单比较。

## 运行

在Linux准备固定运行库与`espeak-ng`后，下载和生成阶段与推理分离：

```sh
python3 -m venv target/voice-quality-venv
target/voice-quality-venv/bin/pip install -r scripts/requirements-voice-probe.txt
target/voice-quality-venv/bin/python -m unittest discover -s scripts -p test_voice_quality.py -v
target/voice-quality-venv/bin/python scripts/voice_probe.py prepare
target/voice-quality-venv/bin/python scripts/voice_quality.py synthesize
target/voice-quality-venv/bin/python scripts/voice_quality.py evaluate
```

评测进程在导入numpy/sherpa、加载模型之前必须安装Linux libseccomp socket系统调用拒绝策略，实际验证IPv4/IPv6 socket返回EPERM；失败不回退到联网运行。复用已审计的开发探针过滤器，不是通用沙箱，不保证封闭继承文件描述符/io_uring通路，也不等于手机飞行模式。CI在此基础上额外使用`unshare --net`网络命名空间，在创建命名空间后降回runner的实际UID/GID；原子报告维持0600，不为归档改成全局可读。

默认报告不写参考/识别正文，仅有固定case ID、SHA和指标；这不是匿名化承诺。仅对该非私密固定合成集显式加`--include-text`才归档中英文原文及全部坏例。没有任意录音输入、麦克风采集或上传能力。每轮完成即原子写入部分报告，只有全部轮次结束才`complete=true`；新运行开始先清除旧成功标记，失败只记录异常类型，不能残留旧完整结果冒充本次成功。

原`voice_probe.py recognize`的单句三片段门禁继续执行；新增整句诊断不删旧断言。两者共享同一100ms不按真实时钟馈入、greedy CPU解码和1秒零尾块路径；模型加载另记。报告的计算RTF、加载、Linux进程峰值RSS均不是手机首字/尾字、麦克风往返、冷启动统计或耗电。解码完成不代表通过质量门槛；当前不设事后挑选的CER“通过线”。

## 当前验收与后续

首片源码已有16项本地回归通过，包括961组独立距离对照、S/D/I与长度恒等式、加权汇总、Unicode/数字/否定边界、空结果、原音频/定义篡改和截断、默认不写正文以及失败不残留旧成功。固定模型已真实重新下载/逐项校验；新12句推理与CI结果将在实际完成后补存，当前不预记成功。

真人普通话、口音、噪声、蓝牙、中英混说、姓名/术语、三星/ARM设备及真实按键竞争仍待独立验收。现有仓库规则只用合成测试；本轮未下载或保存真人语音，也没有豆包对照或自然语音识别率。未来自然语料需先明确授权、数据来源、署名、纳入标准与隐私规则，再建立独立结果表。
