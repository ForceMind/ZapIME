# VoiceKey 引入基线 · 实际产物核验

来源为 `e24f1bd21ecd4fca99d909a972116e5a36e5f6c2` / run `42199407235` / artifact `13494970099`。本轮重新读取 GitHub metadata，完整下载 234930585 字节 ZIP，独立计算 SHA256 `0669c42660866c3ed41ff2126810f42f15453bfe30f30f84ae4f203cc345271e` 与公布值一致。二进制与工具仅在 ignored target，不进入 Git。

两个 APK 均实际读取并核对成员、DEX、原有固定运行库 hash、模型清单与许可资产：

| ABI | APK 字节 | SHA256 |
| --- | --- | --- |
| arm64-v8a | 51677499 | `c4c7d4eebc65add88f0b3adb1a44e8606e14422d5ad4d7e004a0acf9efc4e774` |
| x86_64 | 53000785 | `60b94bcb8acf726e3d1934c57465f738a8da31e59314afe72daffe07202fd0b6` |

VoiceKey 的七个 JNI 动态导出齐全，两个 ABI 的 ELF machine 正确；包内五个原生库的 LOAD 对齐均至少16KiB，偏移/虚拟地址同余。VoiceKey DT_NEEDED 只有 libdl/libm/libc。产品DEX含第二引擎类型、不含已知测试探针描述符；未发现已知模型/音频扩展成员，X-ASR固定依赖hash/六成员目录仍通过原检查。二进制静态检查不能证明任意字节都无秘密，离线模块编译边界仍需与锁定源审计配套。

从Google官方SDK repository metadata解析 build-tools35 的包尺寸与SHA1，实际下载并核对；工具ZIP SHA256为 `bd3a4966912eb8b30ed0d00b0cda6b6543b949d5ffe00bea54c04c81e1561d88`。使用其apksigner.jar与zipalign真实执行两个APK的verify/`-c -P 16 4`，四条命令均退出0。两包均v3签名验证通过，开发证书SHA256相同：`e6fc2a324ca8dd88a5c9a74dcf2a9555428281c056013e6071e0887fed637f2c`。这不是正式发布证书，也不能保证与其他runner包覆盖升级。

manifest禁止明文流量、存在输入法/麦克风所需声明且没有新增广泛存储权限；原始aapt2/apksigner文本按字节保留。VoiceKey作者及固定提交署名存在，GPL资产与根许可一致。**基线的Rust许可collector只遍历主核心，未包含独立适配器的tokio/rayon/async-trait等依赖许可，不能把署名文件存在当作完整许可打包通过。** 后续锁依赖和单独归档清单仍为门禁。

此基线仍有 `startVoice()` 只检查X-ASR模型的问题。这里没有运行 VoiceKey 实际模型、Android JNI解码、自然语音或ARM代码；不称为双引擎专项通过或S25可用Alpha。第三方模型再分发权限没有由作者源码授权自动推导。后续源码/包必须独立验证，不能借本基线哈希作为新包哈希。
