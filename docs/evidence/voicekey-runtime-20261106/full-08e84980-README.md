# 08e84980 完整双 ABI 候选 · 实际产物核验

源码 `08e84980562ecc8fab436ffcdab6e5aee8c567e7`，Android run `42221304627`，artifact `13503554301`。完整ZIP已实际下载，234974569字节，SHA256 `5e18aebd7a15b575acc8c01d0ea8bb77c44bce222b9e35d6084d6da23b2aec73` 与GitHub metadata一致。只提取两产品APK和下列原始JSON，未提取/上传VoiceKey权重。

| ABI | APK 字节 | SHA256 |
| --- | --- | --- |
| arm64-v8a | 51785617 | `6f6b156c1872de23d76b0981120147143f46bb6f5378bc5ff978a9f8aba73f13` |
| x86_64 | 53108903 | `cce3801275b21e2cedd43fd126bab690e0f4f1b83ea9de26e51010f343122695` |

两产品实际通过精确源码check_apk：32包独立许可及逐文件哈希、源码Cargo.lock哈希、8JNI导出、仅libc/libm/libdl动态依赖、五库ELF/16KiB、manifest。两个DEX也实际解析确认8个native完整descriptor，包含新native `finish(JI)[B` 和非native Java wrapper `finish(J)[B`。这些静态声明不单独证明Rust调用约定或实际模型时限。

使用已按Google官方SDK metadata核验的build-tools35，两包的apksigner.jar verify --verbose --print-certs及zipalign -c -P 16 4均实际退出0，两包均v3签名通过。两ABI共用本轮开发证书SHA256 `08582031e115a8c819d3e7cf7105e5f683d04fec65b32aab40959b39d32b9547`，不同于此前产物（也不同于同源码focused runner）。不能承诺跨runner直接覆盖升级，不是正式签名；试用前须保留可恢复备份。

本ZIP的API28原始JSON另外证明：model-free VoiceKey JNI边界5例PASS，finish_timeout_contract_checked=true；真实Editable确认23例PASS。确认探针所用识别文字来自原X-ASR，native-report明确模型为x-asr-streaming-int8-2026-06-05；3权限case仍是生产helper的模拟ContextWrapper状态，不是OS权限撤销。非法finish budget契约通过不等于真实VoiceKey模型超时/资源回收或系统IME流程通过。

本报告不代表VoiceKey实际模型识别、自然麦克风、S25/ARM执行或完整Alpha验收；各自专项保留真实结果。二进制与工具仅在ignored target，仓库只保存metadata和原始工具文字。
