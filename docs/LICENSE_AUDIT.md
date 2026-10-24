# 许可审计 · 2026-10-20

本表是固定版本的工程引入审计，不能代替发布时对最终依赖与产物的清单核对。

| 组件 | 核验版本/范围 | 授权与处置 |
| --- | --- | --- |
| MSIME | 97d652a49cabb0887355b84186af2aa9032d3814 | 根 LICENSE + workspace GPL-3.0-only；submodule 原样保留源码、版权、第三方通知；当前链接 engine/path-trust |
| chinese-ime-lm | 引擎依赖 15caab4fdeb59c11f452b91884371cf30e2e5608；另审查最新 4e745be7 | reference/ 的 Cargo.toml 与 LICENSE 为 Apache-2.0；根训练工具 GPL-3.0。随包复制精确依赖版本的 NOTICE 和 Apache 文本 |
| 模型权重 | model-v1，未随首包分发 | 权重 Apache-2.0；再分发保留 safetensors attribution 及 C4 ODC-BY、LCCC MIT 署名；不能用根 GPL 推断全部目录 |
| msime-cloud | 85fe4cebe474abbf03e662d95bbcc0b1ed38f1ee | 根目录无 LICENSE/COPYING。THIRD_PARTY_NOTICES 不是第一方许可；只研究 Go 依赖、路由与 E2EE 公共行为，不复制实现 |
| 拼音/英文数据库 | dict-v2.0.24；资源锁 SHA-256 | manifest 明确 includes_unlicensed_inputs=false，拒绝未授权输入；保留 rime-ice、SCOWL、ECDICT 等来源通知。数据库按逐项许可，不称全部 Apache/MIT |
| 词库源码清单 | 发布引用 71dc6f6cc7a451231ee29c0a39b39adbf2e04d32；审查最新 63c0db54 | 未授权 CustomPinyinDictionary 旧快照、whitelist、商业 OALD 词形必须排除；发布 manifest 已排除。部分 rime-ice 历史补充来源仍有记录不全的残余风险，正式发行前复核 |
| n-gram 二/三元模型 | 未随首包分发 | 来自中文维基百科统计，CC-BY-SA 4.0 署名与相同方式共享义务；不能作为无通知二进制偷偷打包 |
| 引擎嵌入资源 | 见 third_party/licenses 与 vendor/msime/resources/licenses | Unicode、libhangul、chaizi、vi、ewts、rink/MPL 等各自通知保留。未主动启用其他语言不表示链接二进制没有嵌入其数据 |

## 源码与二进制分发

ZapIME 新适配代码按 GPL-3.0-only；不得删除上游作者版权或以改名消除义务。开发包也不得脱离对应源码分发。对接收者提供完整对应源码、固定上游、构建脚本及适用的安装资料；仅提供无法访问的 Private 仓库链接不够。正式发布前生成 Rust 传递依赖 notice/SPDX 清单，核对最终 APK、Apple bundle 和 Windows installer。当前不是完成发布许可验收。

## Android 首包范围

首个构建只取拼音、英文、SCOWL notice 和发布 manifest；没有神经模型、n-gram、日文或上游品牌图像。后续 Android 原生语音依赖见下面的单独审计，不随 APK 带模型。所有下载先校验固定锁；运行时无下载与网络权限。third_party/NOTICE.txt 标明改动日期及与官方上游的区别。

## 实际核心依赖清单

`scripts/collect_rust_notices.py` 按指定 Android target，从 zapime-core 的非 dev 依赖图递归收集包和许可文件，随 APK 放入 assets/rust-licenses/inventory.json 及逐包目录。当前数据切片共 111 个条目（含部分构建期工具）。根/路径 crate 的 GPL 文本统一打包。

打包遗漏许可证文件的 ewts 0.1.4、exmex 0.21.0、jni-sys-macros 0.4.1、rink-core 0.9.0 用精确版本映射补齐。exmex 与 jni 宏的 MIT 文本取自 crates.io 包 `.cargo_vcs_info.json` 对应提交（分别 3ef98341…、64d77b7a…）；ewts 使用上游 MSIME 随附的 Maxim Zommer MIT 通知；rink-core 保留 MPL-2.0 全文及源仓 https://codeberg.org/tiffany/rink 的来源声明。vi 0.8.0 的 Cargo 通过 license-file 声明 MIT，不能因 license 字段为空误报无许可。

这补齐了许可文本包装，不消除上游词库历史来源记录不全或正式发行前的完整源码/安装资料审核要求。

## 2026-10-22 原生宿主与独立服务补充

Apple/Windows 最小宿主参考固定 MSIME 的系统集成约束，保留 GPL 与根 NOTICE；未修改固定子模块。Windows 额外使用 nlohmann/json v3.12.0（MIT），构建归档 SHA-256 固定为 42f6e95cad6ec532fd372391373363b62a14af6d771056dbfc86160e6dfff7aa，MIT 文本随开发包打包。

独立服务第一方代码 GPL-3.0-only。Go 运行时模块清单包括 pgx/v5、pgpassfile、pgservicefile、puddle/v2（MIT）和 x/sync、x/text（BSD/PATENTS）；实际版本由 services/sync/go.mod/go.sum 锁定。collect-notices 工具从实际命令依赖图收集全部 LICENSE/NOTICE/PATENTS，任何缺失即失败，容器包含该清单及 Go LICENSE。没有导入 msime-cloud 实现。此记录不是生产发布的最终完整法律/漏洞审计。

## R2 服务依赖补充

AWS SDK for Go v2（核心 v1.47.3、credentials v1.20.9、S3 v1.114.4）及 smithy-go v1.28.5 采用 Apache-2.0，按 go.mod/go.sum 固定。运行时依赖通知工具已收集 19 个模块的 LICENSE/NOTICE/PATENTS 并随容器打包。Docker/PostgreSQL/Caddy 由其上游镜像提供；MinIO AGPL-3.0 源码 9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a（RELEASE.2025-10-15T17-29-55Z）由 CI 独立构建，只用于隔离的 S3 协议测试，不随 ZapIME 生产容器分发，不把兼容测试说成 Cloudflare 实际账号验收。

## 九键与离线语音原型（2026-10-23）

九键复用固定 GPL-3.0-only 引擎，无上游更新。开发 CPU 探针使用 sherpa-onnx / sherpa-onnx-core 1.13.8（Apache-2.0，内含 ONNX Runtime 须按其 MIT 及第三方 notices 审计）和 NumPy 2.5.4（BSD），未打包进 APK/服务。X-ASR 来源 Gilgamesh-J/X-ASR（Apache-2.0），模型归档及实际四成员 SHA/长度固定；保留 X-ASR-Apache-2.0.txt，下载目录附 LICENSE/NOTICE，不在 Git 分发权重。eSpeak NG 仅开发工具，用于本地合成固定测试句，未捆绑产品。

SenseVoiceSmall 转换权重按固定上游记录适用 FunASR Model License v1.1，含保留出处/作者/模型名、行为与后续条款修订等独立条件，不等同框架 Apache/MIT；当前未引入。Fun-ASR-Nano 的具体模型/解码器须重新逐项审查，不泛化同一家项目的许可。模型性能和其他模型卡的许可证标签不能替代权重版本本身的授权核查。正式语音发行仍需补齐完整 native 运行库、模型与资源清单。

## Android 原生语音引入（PR #32）

复用固定 MSIME `shared/voice/LocalAsr.cpp/.h` 与其 Sherpa C API 头，不改 vendor；GPL-3.0-only 适用。sherpa-onnx 1.13.8 对应源码 `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`，Android AAR 50,129,134 字节、SHA256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`。AAR 没有 LICENSE/NOTICE，不能直接无通知分发。只打包 ARM64/x86_64 的 C API 和 ONNX 原生库，逐库哈希见 `third_party/android-voice-lock.json`；不包含 Java/Kotlin 上游主机代码。

APK 保留固定上游的 Sherpa Apache-2.0、ONNX Runtime MIT 与完整 ThirdPartyNotices；实际原生 ORT 的版本字符串为 1.28.2，与固定上游的 v1.28.2 通知一致。nlohmann/json 3.12.0 固定压缩包/单头 SHA，MIT 文本随包；NDK C++ 静态库的 toolchain/sysroot NOTICE 同时保留。构建核对不需要未捆绑的 libc++_shared。不是对所有二进制内第三方组件的正式发布审计替代。

离线模型 ZIP 只包含固定四个推理文件、X-ASR Apache-2.0 全文及 Gilgamesh-J/X-ASR、k2-fsa/sherpa-onnx 转换出处 NOTICE；权重不提交 Git，不随 APK/云服务分发，可独立构建/通过 CI 产物分发。eSpeak NG 和 Python/NumPy 仅测试合成与打包工具，不进入 Android 产品。正式产品仍须提供对应 GPL 源码及构建资料，保留所有必需 notice。

## Android 选区回声迁移（PR #39，2026-10-23）

从既有固定 MSIME 97d652a49cabb0887355b84186af2aa9032d3814 迁移 `platforms/android/java/app/msime/android/core/SelectionEchoTracker.java` 及 `platforms/android/tests/core/SelectionEchoTrackerSmoke.java`。上游根 GPL-3.0-only 适用，原文件没有单独版权头；保留完整原始说明/测试，新增来源、贡献者归属及修改日期头。仅改包/import，并用 Math.min/max 替换同义 BoundsPolicy 调用；未更新/改写 vendor 子模块。宿主的重启处理独立适配 API30 EditorInfo 初始快照，不复制上游 UI 线程远端上下文读取。对应源码、根 GPL 文本及本 NOTICE 随既有分发义务继续提供。

后续一并适配该固定MSIME `MSIMEInputService.java` 的pendingCompositionMarkerLoss衔接：暂缓100ms的同位置标记丢失回报、重启取消及新按键前结算。原源码/贡献者/GPL保持可追溯；ZapIME使用异步worker重置解码器且不向应用新选区写空组字，仍不复制其UI线程getTextBeforeCursor路径。

## 英语伴学候选（开发研究，未引入客户端）

2026-10-24核对Helsinki-NLP/opus-mt-zh-en官方模型卡：权重为CC-BY-4.0，不是Apache-2.0；CTranslate2与ONNX Runtime运行库分别为MIT。产品没有打包这些权重或增加SDK；开发研究已另行下载并转换一个固定原始模型包，不能把许可调研/研究探针当成客户端发行审计完成。正式引入须固定revision/SHA，保留作者/模型来源/许可证和量化转换说明，验证分词与各平台运行包；详见ENGLISH_LEARNING.md及其中官方来源。语音模型或拼音候选模型的既有许可不能覆盖此翻译模型。

### 独立CPU探针的实际包内许可核查

开发探针使用官方模型卡所链接zho-eng/opus-2020-07-17.zip，已核实整个归档SHA/大小及其内部LICENSE明确为CC-BY-4.0；保留原文/README在third_party/notices。数据仓库根的训练数据NC许可不直接套到具有单独声明的此包，HF转换版声明也不代替原包实际检查。权重和运行库二进制均未提交；本次观测hash不是独立审计的发布者签名。可复现步骤、全部合成译文与运行库分发待审项见ENGLISH_TRANSLATION_PROBE.md。

原始LICENSE包含行尾空白与末尾空行，按原字节/SHA保留；仅对这一文件关闭Git文本换行转换与空白告警，不修改许可文本或放宽其它源码检查。

### 可选翻译研究预处理（2026-10-24）

独立开发探针显式下载Moses固定提交08e782040189e0abb3b43a4ec9245e6179e39eca中的replace-unicode-punctuation.perl、remove-non-printing-char.perl与COPYING到ignored target；两脚本文件头为LGPL-2.1-or-later，逐项SHA/大小核验并保留源文件与COPYING。没有复制脚本或其许可文本到产品、没有新运行库或模型随APK分发。ZapIME适配/测试脚本为本项目GPL-3.0-only，OPUS原权重CC-BY-4.0与CT2 MIT仍分别记录。未来实际发行helper/模型/运行库须另审查源码和署名义务，不将本次开发下载审计当移动端分发完成。详情ENGLISH_TRANSLATION_PREPROCESSING.md及其原始manifest。
