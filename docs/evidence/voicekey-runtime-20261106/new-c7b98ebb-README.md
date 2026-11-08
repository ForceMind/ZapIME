# c7b98ebb 固定候选 · 实际产品包核验

源码 `c7b98ebb7a0707ea622a6799a8d1d0246751e759`，Android run `42213679453`，artifact `13500587615`。完整 ZIP 已实际下载，234980271 字节、SHA256 `e891aab11d24f659309d591bb2bf23bb3a16e0deb89a5480cc5848c6f5325695` 与 GitHub metadata 一致。后续测试诊断提交不能冒称是这个 APK 的源码；本记录仅对应上述固定 revision。

| ABI | APK 字节 | SHA256 |
| --- | --- | --- |
| arm64-v8a | 51781521 | `ae9c4049fe9ebd66aa6c251341a0455d22804265a099feea8504a9bcba89a0a0` |
| x86_64 | 53108903 | `6f0878de2ed0a9708ec52963f1bf1315360b8ce9da4c778c91b30805aae64c53` |

两个产品 APK 均实际通过固定源码 `check_apk.check_contents`，验证器、prepare_android_voice 与 Cargo.lock 内容也和该 revision 逐字比较相同。检查包括原有固定 X-ASR 库/目录、独立 VoiceKey 32 包许可文本与逐文件哈希、精确源码 Cargo.lock 哈希、8 个 JNI 动态导出（含 releaseCache）及仅 libc/libm/libdl 的 DT_NEEDED。五个原生库分别检查 ELF machine、LOAD 至少16KiB对齐及偏移/虚拟地址同余。DEX 中有双引擎类型、没有已知测试探针描述符，未发现已知模型/音频扩展成员；这些静态检查不泛称任意字节无秘密。

使用已按 Google 官方 SDK metadata 核对的 build-tools35，两个 APK 的 aapt2 manifest、apksigner.jar verify --verbose --print-certs、zipalign -c -P 16 4 均实际退出0。两包均 v3 签名验证通过，共用本轮开发证书 SHA256 `a17d38115ce78a43debf2c1c9c2ef72a0c26c131dd708fb74c7073eae5b6b2db`。该证书不同于 e24f1bd 和 5bbfe310 产物；不保证跨 runner 覆盖升级，不是正式发布签名。不得要求删除唯一未备份资料。

双 ABI 构建/API28既有回归 job `127880158440` 已 success；这不代替 VoiceKey-only 或现代系统专项结果。实际安装、VoiceKey 模型解码、AudioRecord/Editable、已激活系统 IME、ARM 数值执行、S25及自然语音须按各自证据验收，不能由本静态报告推导。二进制和工具仅保留在 ignored target；仓库仅保存 metadata/原始工具文字，未提取、上传或再分发 VoiceKey 权重。
