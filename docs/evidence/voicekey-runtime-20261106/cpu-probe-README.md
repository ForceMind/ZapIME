# VoiceKey CPU 模型专项入口

`.github/workflows/voicekey-model-probe.yml` 分别在原生 x86_64 与 aarch64 Linux runner 运行；不以 x86 测试替代 ARM。模型由 runner 从锁定官方来源直接取得，原包、全部外层/内层成员尺寸及 SHA256 必须匹配 `third_party/voicekey-model-lock.json` 的 PINNED 记录。下载与推理是不同进程；推理进程继承 Linux libseccomp socket 限制并实际自检 IPv4/IPv6 socket 创建被拒绝。该限制的继承描述及边界写入报告，不能冒充手机飞行模式或通用安全沙箱。

固定合成 PCM 由既有 `voice_probe.py` 的中文测试句与 eSpeak 生成；音频与真人语音无关。Rust example 调用真实模型解析、模型前向及解码，覆盖无样本、全零、正/负1 LSB、尾块、取消和重复加载。实际模型不可取得或信任锁不完整时明确退出 BLOCKED，不制造识别结果。

```sh
python3 scripts/voicekey_cpu_probe.py prepare --report target/voicekey-probe/prepare.json
bash scripts/probe_voicekey_model_import.sh target/voicekey-probe/source/official-package.apk
python3 scripts/voicekey_cpu_probe.py fixture --report target/voicekey-probe/synthesis.json
cargo build --locked --manifest-path integrations/voicekey/Cargo.toml --release --example offline_probe
python3 scripts/voicekey_cpu_probe.py run --rounds 3 --report target/voicekey-probe/report.json
```

上述 Java 入口另行验证真实原包通过生产模型存储器的固定目录、成员校验和原子发布，并不使用伪模型替代。CPU 模型目录为 `target/voicekey-probe/model`；它与 Java probe 的临时发布目录互相独立。

默认报告只保存正文哈希、码点数与版本化 CER 的错误计数，不保存识别正文。仅固定公开合成素材可以显式使用 `--include-text`。报告包含模型/锁/PCM SHA、线程策略、喂入模式、模型准备校验耗时、模型启动、首次观察 partial、停止到定稿、CPU 秒及进程 RSS；能耗没有测量。默认 unpaced 喂入每块100ms音频，可用 `--paced` 另外模拟实时喂入，不混为一个延迟指标。

`release_before_start` 与 `repeated_start` 均在物理 worker 退出后重载模型；当前 worker 会释放权重，因此后者不代表常驻模型缓存命中。完整性/模型解析已经预热 OS 页缓存，不能称手机冷启动。每组3轮只用于形成可追溯 P50/P95，仍是 **1条不同语音样本**；不代表自然人声识别质量、Android/JNI/AudioRecord、真实系统 IME、S25 手机或用户确认体验。RTF 不是端到端延迟，CER 不是准确率。

模型再分发许可尚未建立。工作流只上传精确列出的 JSON 和来源/范围文本，**不上传模型、原包、WAV、PCM 或宽泛 target 目录**。产物不构成公开权重授权或双引擎 Alpha 最终验收。
