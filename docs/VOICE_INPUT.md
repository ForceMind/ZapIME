# 九键与本地语音

## 实际边界

Android 新增真正九宫格拼音键盘，复用固定 MSIME 九键解码器。空闲工具栏可切换「九键 / 全键」并在本机保存；英文和密码字段仍用全键。增加读音选择、退格、分词、数字面板与本地词/短语匹配。读音/候选携带 generation，拒绝迟到点击；显式锁定的读音也约束个人词，不把 mihao 混入锁定 ni 的候选。九键置顶保存候选的完整拼音，与全键共享；不把数字同码的置顶越过已锁定的不同读音，部分前缀候选不冒充整段置顶。共享核心测试已通过；Android 系统验收见 STATUS.md，另外三端尚无九键界面。

**Android 开发版已接入原生离线语音，PR #32 的 API28 模拟器门禁通过。** 包含模型导入、AudioRecord、独立解码线程、预览/停止/取消与确认插入；显式 RECORD_AUDIO 权限，仍没有 INTERNET 权限。真实 JNI 合成音、模拟器静音录音及独立 App UI 分别验证；尚不代表自然语音、ARM/三星、现代系统或四端语音已完成。下述旧探针数据继续保留来源与边界。

## Android 模型安装与使用（开发验证版）

1. 取得同一构建的 ARM64 开发 APK 与 `ZapIME-XASR-model.zip`。Android Actions 成功产物 `ZapIME-Android-development` 包含 APK、离线模型包与合成测试证据；GitHub 产物有保留期限，后续可从固定源码重新构建。当前不是正式签名发布。
2. 在主 App 打开「离线语音 · 模型与权限」，显式选择 ZIP，等待逐文件校验完成，再允许麦克风。模型只导入到本 App 私有目录，不要求输入服务器地址。
3. 返回普通输入框，完成已有拼音组字，长按空格或在键盘「工具 → 离线语音」开始录音。轻点空格仍是原来的空格/选词。停止后查看文字，点击「确认插入」才会写入正文；可直接重新录音，取消会丢弃这一轮。密码/禁止学习字段不开放此入口。
4. Back 先取消语音回到键盘；语音工具栏可显式收起。隐藏键盘、切换输入框、移动光标、配置变化都会取消当前语音。录音结束到确认期间也重新检查连接、选区代次和有界上下文；上下文不可读时拒绝开始。不支持把旧识别结果自动写到新位置。

可复现模型包仅使用 Python 3.11+ 标准库，无需安装 Python 推理运行库：

```sh
python3 scripts/build_voice_model.py --output target/ZapIME-XASR-model.zip
```

此次本地实际生成 ZIP 129,097,503 字节，SHA256 `1e1ddde9def3868adac23842cdc5db42ad7d84e39452653a829d977f77a44fd1`。ZIP 压缩字节可能受 zlib 版本影响；**App 信任 APK 内固定的每成员长度/SHA，不依赖此整包哈希**。四个推理文件总计 169,227,953 字节，另附 LICENSE/NOTICE；安装需要足够的暂存空间，重导入期间旧有效模型保留到新包校验完成。清除 App 数据/卸载会删除模型，模型可重新导入；本模型不是个人资料或 E2EE 恢复包。

模型目录不在用户资料 JSON 备份中，也不上传 R2。导入拒绝未知名称、目录、路径穿越、重复成员、截断、超长及校验不符；不执行包内清单，最后写入 APK 可信清单。校验失败不覆盖已有模型；发布前后的目录移动使用同文件系统 atomic move，但突然断电/进程终止的两次目录移动之间仍可能需要重新导入，尚未做断电恢复验收。

## Android 执行边界

- 固定未改动的 MSIME `LocalAsr.cpp`，C++17 JNI；sherpa-onnx 1.13.8、ONNX Runtime 1.28.2 两个 ABI 逐库锁哈希，校验 ELF 架构、16 KiB LOAD 对齐和动态依赖白名单。依赖完整通知随 APK，模型不内置；默认不使用热词或远程润色。
- 模型逐项复核和原生解码在独立语音 worker，不阻塞 Rust 按键队列/SQLite reader。录音另有 capture 线程：16 kHz 单声道 PCM16、100 ms 块、有界全程内存队列；最长 60 秒墙钟与 960,000 样本，识别采用 2 个 CPU 线程。
- 开始/确认时的有界编辑器上下文查询也在语音 worker；不把可能等待数秒的跨进程读取放在键盘 UI。界面保持可取消，返回主线程后再次核对 ticket/epoch/选区。依据 [Android InputConnection 文档](https://developer.android.com/reference/android/view/inputmethod/InputConnection#getTextBeforeCursor(int,int)) 和 [AOSP AnyThread 远程读取实现](https://android.googlesource.com/platform/frameworks/base/+/70908ddf269e69d3a177f0c873cddc48a79d6f78/core/java/android/inputmethodservice/RemoteInputConnection.java)，不把 EditorInfo 初始缓存当作当前确认位置。
- 不写录音文件，不上传音频/文字，不自动学习语音正文，不做云端回退；正文只保留在当前预览内存，取消后释放。模型缓存闲置约两分钟后释放，内存紧张时取消并释放未使用模型。并非对低内存设备的峰值内存/耗电验收。
- JNI 分配前就建立取消句柄，限制最多 4 个；start/feed/finish/close 必须同一所有者线程，跨线程只允许 cancel。生命周期取消不等待模型加载或解码完成，迟到回调由独立 ticket/输入 epoch 拒绝。
- 模拟器 JNI 合成 PCM 识别、实际 AudioRecord 静音录制、键盘预览/焦点保护是不同验收。合成 PCM 直接进入原生解码器，不冒充通过手机麦克风说话；静音结果也不能证明自然语音质量。SAF 文件选择、现代系统后台麦克风/AppOps、蓝牙、ARM/三星和真实口音仍需真机验收。

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

## Linux 断网探针证据

[39898390340](https://github.com/ForceMind/ZapIME/actions/runs/39898390340) 在无网络 namespace 实际执行三个流式识别轮次，中文片段断言通过；源代码 b50fbbd6585f041abce3751fd27df93e9fd00ebb。完整原始报告见 evidence/offline-voice-ci-39898390340.json：CPU 2 线程、7.843 秒音频、加载 1.719 秒、中位计算 0.698 秒（RTF 0.089）。CI 输出仍出现「一转」而非「一段」，因此不能把片段通过说成整句完全准确，更不能推广为自然录音/方言/手机验收；准确率、断网边界、吞吐和交互延迟分别记录。


## Android 原生与 UI 最新证据

源码 `308085b76b6c3de8c34120f5ebfef833f764285a` 的 [40009811125](https://github.com/ForceMind/ZapIME/actions/runs/40009811125) 已整体 success：双 ABI 原生/APK与最终包逐库哈希；API28原有两轮全键/九键/资料/隐私/真实退出；真实JNI合成中文片段、600块/60秒样本上限、所有者/取消、拒绝权限和实际AudioRecord静音录制/停止。独立 App 的真实 Editable 验证预览不自动上屏、重录、长按空格、光标/焦点、Back取消、显式隐藏窗口真正消失后重开/复录，并实际切回AOSP等待服务退出及拒绝fatal。暂停宿主的1800ms取消响应使用IME窗口内真实按钮的无障碍动作；其它普通语音操作是触屏，不混为物理布局证明。

[原始报告与图像](evidence/voice-android-40009811125/) 未重绘/裁剪。已下载完整233486804字节artifact，实际SHA256 `1f6170557d0967d5de741d3eabfb4f1420012330739effee3a809aa129b6e3cf`；逐文件校验在 provenance.json。JNI合成125490样本/7.843125秒，CPU配置2线程，recognizer加载1431ms，含JNI的计算1215ms（RTF0.155）；这不含模型逐项SHA/原生库初始化，不按真实时间采音，不是手机冷启动、首字或端到端延迟。UI图片来自静音，识别准确率仅固定合成片段断言，未冒充自然语音或豆包同等质量。

同head其它检查也已success：三系统共享核/真实Apple构建/实际执行3项iOS模拟器测试40009810845，Windows40009810864，真实PG/S3/安装及灾备40009810903，独立断网CPU40009810833。仍不表示本功能已接Apple/Windows，也不表示四端联网完成。后续 [PR #33](https://github.com/ForceMind/ZapIME/pull/33) 扩展API35/36，同一APK/模型复用，未完成前不提前声称现代系统通过。

自动审阅随后发现结束队列的尾块竞态，当前候选改为先观察完成再复核队列空，并增加稳定控制该时序的回归。上述308085b结果仍是该旧源的证据；尾块修正版另行跑完整CI，合并前不沿用旧成功。
