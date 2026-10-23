# 九键与本地语音

## 实际边界

Android 新增真正九宫格拼音键盘，复用固定 MSIME 九键解码器。空闲工具栏可切换「九键 / 全键」并在本机保存；英文和密码字段仍用全键。增加读音选择、退格、分词、数字面板与本地词/短语匹配。读音/候选携带 generation，拒绝迟到点击；显式锁定的读音也约束个人词，不把 mihao 混入锁定 ni 的候选。共享核心测试已通过；Android 系统验收见 STATUS.md，另外三端尚无九键界面。

**语音是独立推理原型，尚未接入 APK 麦克风或四端宿主。** 当前包仍不申请录音或网络权限；不把上游已有代码、CPU 探针当成产品语音已完成。

## 模型与许可（2026-10-23）

| 方案 | 用途 | 核查 |
| --- | --- | --- |
| sherpa-onnx + X-ASR-zh-en 流式 INT8 | 边说边出字，中英、标点；优先移动端评估 | 框架 1.13.8，Apache-2.0；模型来源项目 Apache-2.0。固定归档 133,895,136 字节，四个推理文件 169,227,953 字节，不是内存占用测量 |
| SenseVoiceSmall INT8 | 说完一句后快速识别，中英/粤等语言 | 转换权重约 228 MiB，独立 FunASR Model License v1.1；不能沿用框架 Apache 许可。此次未引入 |
| Fun-ASR-Nano 等大模型 | 桌面精度档 | 按具体权重版本与解码器分别审计，未引入、不承诺手机速度 |

固定 MSIME `resources/local-asr-models.json`、`voice-runtime.lock.json`、`shared/voice/LocalAsr.cpp` 已有跨端本地语音参考；不直接带入其默认云服务、Token 与联网润色。

来源：
- https://github.com/k2-fsa/sherpa-onnx
- https://github.com/Gilgamesh-J/X-ASR （模型支持真流式、多个 chunk、标点与中英；其成绩不是 ZapIME 实测）
- https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html
- https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE

豆包官网 https://shurufa.doubao.com/pc 展示方言、术语、中英混输等能力，公开资料不足以确定其默认识别完全本地或推断所用模型，不能直接宣称开源模型达到相同体验。

## 可复现探针

```sh
python3 -m venv .venv-voice
.venv-voice/bin/pip install -r scripts/requirements-voice-probe.txt
.venv-voice/bin/python scripts/voice_probe.py prepare
.venv-voice/bin/python scripts/voice_probe.py synthesize
.venv-voice/bin/python scripts/voice_probe.py recognize --wav target/voice-probe/synthetic.wav
```

`synthesize` 需要 espeak-ng，只合成固定测试文本，不采集麦克风。`prepare` 才联网，核验固定模型归档及逐项长度/SHA，只提取四个常规推理文件，不执行归档代码、不提取链接或原始测试录音。权重不提交 Git、不随现有 APK/同步容器分发。模型旁保存 LICENSE/NOTICE，锁位于 third_party/voice-probe-lock.json。

`recognize` 只加载校验过的本地文件、强制 CPU，默认报告不含正文；`--include-text` 仅供非私密合成样例。记录加载、三轮计算耗时/RTF、首次出字时已馈入的音频位置、文本长度和摘要。100 ms 分块尽快馈入，**不是按真实时钟说话**；首次计算时间不能称为端到端首字延迟，RTF 不含用户停顿和宿主上屏。

offline-voice.yml 下载/合成后用 `sudo unshare --net` 禁止推理进程联网，并归档报告和合成 WAV。该 gate 验模型完整性、原生加载、流式计算和固定合成样例三个中文片段（你好/离线语音输入测试/三点开会），任何一轮不包含预期片段就失败并保留报告。它不是广泛中文准确率门禁；eSpeak 机械音不适合替代自然录音质量集。

本地首轮使用 cmn 合成器，9.970 秒音频计算约 0.808 秒，但被误识别为英文，不计中文通过。定位到合成器把词典拼音按英文读出，改用 cmn-latn-pinyin 后同一句得到「你好， 这是一段离线语音输入测试今天下午三点开会」。Linux x86_64 CPU 两线程：7.843 秒音频，加载约 1.629 秒，三轮计算中位数 0.708 秒，RTF 0.090，约 11.1 倍实时计算速度。该单一合成样例不代表自然语音、手机延迟、噪声/口音或豆包同等准确率；首轮错误保留说明，不隐藏。后续补更自然且许可明确的合成音和用户本机自愿录音评估集。

## 宿主接入与验收

1. Android：明确录音手势、可见状态/停止/取消；设置页首次申请 RECORD_AUDIO，AudioRecord 采 16 kHz PCM，模型初始化/解码不占按键线程。失焦、退出、权限撤销即停止；迟到识别不能写进新输入框。
2. 本地识别不自动回退云端，录音默认不落盘、不上传 R2。仅用户主动保存最终文字且开启对应同步时才走既有密文同步。离线模型可下载一次/离线导入，真实展示版本、许可及准备状态。
3. 先做流式可编辑文字、最终确认、本地标点/数字格式/端点检测，再加入本地热词；不默认发给远程大模型润色改意。
4. macOS/Windows 使用本机工作/辅助进程及系统麦克风权限，绑定输入焦点；模型故障不拖垮输入法。
5. iOS 自定义键盘不能直接访问麦克风，需要主 App 录音/本地识别，再以 App Group 和显式返回键盘接力；存在切换成本，须当前系统真机验证。「完全访问」不能替代麦克风权限。Apple 文档：https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/CustomKeyboard.html 。

真机门禁需包括冷/热启动、首字/尾字延迟、峰值内存、耗电、长短句、静音/噪声、蓝牙耳机、口音/方言、中英夹杂、金额/日期/数字、术语、拒绝/撤销权限及焦点切换；未验收前不承诺毫秒指标或同等准确率。
