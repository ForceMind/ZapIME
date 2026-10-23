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

构建脚本只取拼音、英文、SCOWL notice 和发布 manifest；不取神经模型、n-gram、日文、语音及上游品牌图像。所有下载先校验固定锁；运行时无下载与网络权限。third_party/NOTICE.txt 标明改动日期及与官方上游的区别。

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
