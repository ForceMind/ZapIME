# 5bbfe310 中间候选 · 实际产品包核验

源码 `5bbfe310e716762b36cdb0cf28b09ad5a2e1818c`，Android run `42211989441`，artifact `13500326619`。完整 ZIP 已实际下载，234967345 字节、SHA256 `868b854727b6b5879c85f6cdbbe6ad7141318a066baf49ac62b65dbf8575d171` 与 GitHub metadata 一致。后续源码已继续推进；这是固定中间候选的字节核验，不是最终 Alpha 或新提交验收。

| ABI | APK 字节 | SHA256 |
| --- | --- | --- |
| arm64-v8a | 51781521 | `f862aea4b9a6f5287c5edf59cd5dfc5d29e81f82d4153a4164c10d9e62b97d17` |
| x86_64 | 53108903 | `d2d52943b7a67800bf80c05a2c36fa104044c5fae4cdda75dc256946d89fa38e` |

两个产品 APK 分别实际通过 source-locked `check_apk.check_contents`：固定 X-ASR 库/目录、独立 VoiceKey 32 包许可文本与逐文件哈希、Cargo.lock 来源哈希、8 个 JNI 动态导出（包含 releaseCache）、DT_NEEDED 仅 libc/libm/libdl。五个原生库均实际核对 ELF machine、LOAD 至少 16KiB 对齐及偏移/虚拟地址同余。产品 DEX 有双引擎类型、无已知测试探针描述符；未发现已知模型/音频扩展成员。上述静态边界检查不能泛称任意二进制内容无秘密。

使用先前已按 Google 官方 SDK metadata 核对的 build-tools 35，实际执行两个 APK 的 aapt2 manifest、apksigner.jar verify --verbose --print-certs 和 zipalign -c -P 16 4，均退出 0；两个 APK 均 v3 签名验证通过。新开发证书 SHA256 为 `c373305d5e6f559dacd2c3e6458184eb7d72fe10515d575f3ccfc4a915e6b039`，两 ABI 相同，但与历史 e24f1bd 包不同。它不是正式证书，也不能承诺覆盖其他 runner 产物；不得要求删除唯一未备份数据。

本记录不代表安装、VoiceKey 实际模型解码、AudioRecord/Editable、已激活系统 IME、ARM 运行或自然语音通过。上述测试以各自实际 CI/设备记录为准。只提取产品 APK 进行核验，没有提取、上传或再分发 VoiceKey 权重；二进制及工具留在 ignored target，仓库仅保存摘要和原始工具文字。

本轮独立读取的 [CI job metadata](new-5bbfe310-ci-job-metadata.json) 显示：双 ABI 构建与既有 API28/35/36 回归 success；VoiceKey-only 专项 API28/35/36 均 failure。模型专项失败必须保留并由后续固定源码修复/复跑，不能由上述静态包核验抵消。
