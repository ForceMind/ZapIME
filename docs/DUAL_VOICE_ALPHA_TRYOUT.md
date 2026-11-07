# Android 双离线语音 Alpha 候选试用

更新：2026-11-06。本文对应已完成云端模拟器专项的双引擎 Alpha 候选（包含权限复查与背压修补），可供用户和 VoiceKey 作者试用。三星 S25、自然人声与正式发布仍未验收。X-ASR 保持默认，VoiceKey 为实验选项；是否推荐第二引擎，由同一台手机、相同语料的实测决定。

## 固定候选与下载

程序固定源码：[`08e84980562ecc8fab436ffcdab6e5aee8c567e7`](https://github.com/ForceMind/ZapIME/tree/08e84980562ecc8fab436ffcdab6e5aee8c567e7)。PR [#71](https://github.com/ForceMind/ZapIME/pull/71)，跟踪 [#72](https://github.com/ForceMind/ZapIME/issues/72)。后续测试固定于 `be79d084eef1f835e7504f134f46ba315b36df18`：只修文件选择器的测试手指事件、独立模拟器的软键盘设置准备与恢复，以及证据；产品源码与候选一致。该测试提交的 [VoiceKey 专项 run42227414903](https://github.com/ForceMind/ZapIME/actions/runs/42227414903) 已在 API28/35/36 全部通过。固定 APK 来自下面的08构建，不把测试提交的新构建签名或摘要混用。

[固定双ABI构建下载](https://github.com/ForceMind/ZapIME/actions/runs/42221304627/artifacts/13503554301)：登录GitHub后下载 `ZapIME-Android-development` ZIP，解压选择 `android-arm64-v8a/ZapIME-0.1.0-dev-arm64-v8a.apk` 用于ARM64手机试用；x86_64版用于兼容的模拟器。ZIP的实际字节/摘要及APK签名/16KiB/JNI/许可/DEX均已独立复核，见[产物记录](evidence/voicekey-runtime-20261106/full-08e84980-package-verification.json)。Actions产物有保存期限，下载后保留固定源码与校验资料；不承诺永久下载服务。

| 文件 | 字节数 | 实际SHA256 |
| --- | ---: | --- |
| ARM64 APK | 51785617 | `6f6b156c1872de23d76b0981120147143f46bb6f5378bc5ff978a9f8aba73f13` |
| x86_64 APK | 53108903 | `cce3801275b21e2cedd43fd126bab690e0f4f1b83ea9de26e51010f343122695` |
| 完整下载ZIP | 234974569 | `5e18aebd7a15b575acc8c01d0ea8bb77c44bce222b9e35d6084d6da23b2aec73` |

开发证书SHA256：`08582031e115a8c819d3e7cf7105e5f683d04fec65b32aab40959b39d32b9547`。两个APK同签，和先前runner/独立focused构建的证书不同。**升级前先备份并保存恢复码；签名不同时不能直接覆盖，不要删除唯一未备份资料。** 这是开发候选，没有正式签名、公证或稳定发布。

固定源码重建需要仓库所锁定的Rust1.97.1、AndroidAPI36/build-tools35/NDK28.2.13676358及JDK17。执行原 `scripts/build_android.sh arm64-v8a`，完整步骤见workflow。临时开发密钥和构建时间戳会改变输出摘要，不宣称不同机器的APK逐字节相同。仅在云端隔离CI运行标记为dedicated emulator的设备验收脚本；不要把这些脚本用于个人手机。

| 验收层级 | 当前事实 |
| --- | --- |
| 独立模型/真实JNI/producer/确认/系统IME | be79 focused API28/35/36 均已实际通过；各14主案例、23Editable（合成JNI文字经生产确认事务，非自然麦克风非空物理确认）、独立125861样本全部接受、30秒受控watchdog、离线录音UI及真实服务退出 |
| 原有输入/X-ASR/资料/隐私/备份 | 固定产品08完整构建的 API28/35/36 原有回归均通过；其中旧 VoiceKey 现代测试驱动失败保留，另由 be79 专项复验通过 |
| ARM原生CPU | 实际LinuxARM数值、26单测、固定模型解码通过；不是AndroidARM手机 |
| 三星S25/自然人声/同机能耗和性能 | NOT RUN，集中操作见[S25验收单](S25_DUAL_VOICE_CHECKLIST.md) |
| 四端完整产品、E2EE资料同步与正式发布 | 未完成，不由本候选代填 |

根[STATUS](STATUS.md)、[验收记录](VOICEKEY_ACCEPTANCE.md)和原始证据保留各次真实失败。旧[Android试用说明](ANDROID_TRYOUT.md)对应其自己的历史包，不混用摘要和通过结论。

## 两套模型分别准备

两套模型独立导入到本机 App 私有目录，选择某一引擎时只准备和验证该引擎的模型。使用 VoiceKey 不要求先安装 X-ASR。APK 不内置语音模型；模型也不包含在个人资料备份或 R2 同步中。卸载或清除 App 数据会删除本地模型，之后可按相同可信来源重新导入。

### X-ASR

取得与候选构建对应的 `ZapIME-XASR-model.zip`（完整下载ZIP根目录，129097503字节，SHA256 `1e1ddde9def3868adac23842cdc5db42ad7d84e39452653a829d977f77a44fd1`），在主 App「离线语音 · 模型与权限」选择「导入离线模型 ZIP」。安装成功后选择「使用 X-ASR（原有离线引擎）」。固定每成员尺寸/SHA 与重建命令见 [语音说明](VOICE_INPUT.md)。同一下载ZIP也带此模型包；固定成员白名单由App复核。不要混用未知来源模型或拿APK摘要当模型摘要。

### VoiceKey 微信离线

VoiceKey 源码固定为 [`J3n5en/voicekey@3ba017826eef7fac405198510b03859eb5b6c8c4`](https://github.com/J3n5en/voicekey/tree/3ba017826eef7fac405198510b03859eb5b6c8c4)。保留作者署名和已确认整合记录；第三方模型使用与再分发条件独立处理，参见[适配器许可边界](../integrations/voicekey/LICENSE)。ZapIME 不启用其非公开云识别协议或提取的第三方服务凭据。

本候选只接受下列固定原始模型包。请先确认自己具有适用的使用权限，从官方来源取得，不从不明网盘或聊天文件替代。

| 项目 | 固定身份 |
| --- | --- |
| 模型 ID | `wechat-offline-20260710220526250` |
| 官方原包 | [20260710220526250_ime_asr_decoder_model.apk](https://download.z.weixin.qq.com/publish_words/v1/ime_asr_decoder_model/20260710220526250_ime_asr_decoder_model.apk) |
| 原包字节数 | `102920639` |
| 原包 SHA256 | `b3729fbe928b25740cc9cd781537175b1db4a445bd78bf464267c0c4633e0863` |
| 所有成员、来源与尺寸/SHA | [固定模型锁](../third_party/voicekey-model-lock.json) |

该文件虽然以 `.apk` 结尾，操作入口是 ZapIME 设置中的「导入 VoiceKey 微信离线语音包」，通过系统文件选择器选择原始文件；不需要把模型包作为应用安装，也不需要授予“安装未知应用”权限。等待完成固定包及成员校验后，选择「使用 VoiceKey 微信离线（实验）」。导入需要原包、内部压缩包与解压文件的暂存空间；失败时保留旧有效模型。

官方来源完整性元数据的[独立审计](https://github.com/ForceMind/ZapIME/actions/runs/42209579465)已通过。哈希相符只能确认取得的是固定字节，不代表再分发权利、模型识别质量或手机验收。当前不将 VoiceKey 权重放进产品 APK、测试 APK 或公开构建产物；官方地址可达性也不作永久保证。本地访问曾得到 HTML 错误页，失败记录已保留；不要把错误页改名后导入。

在桌面可使用 `sha256sum 文件名`、macOS `shasum -a 256 文件名` 或 PowerShell `Get-FileHash 文件名 -Algorithm SHA256` 核对下载；App 导入时仍会独立执行固定校验。

## 安装后集中试用

**已有用户先备份，再安装。** 先导出个人资料并独立保存加密备份恢复码；不同 CI runner 的开发签名可能不同，不能保证覆盖安装。不要为了安装开发候选删除唯一未备份资料。个人资料备份不等于账号令牌、设备授权或 E2EE 密钥的完整恢复包。

1. 核对本页程序APK的SHA后安装。系统若要求，只允许当前下载/文件App安装这一次程序，安装后可关闭该权限。VoiceKey模型的`.apk`只在设置中导入，不作为应用安装。打开主 App，按提示在系统中启用并选择闪电输入法。先在普通输入框验证拼音、九键/全键、退格、候选和资料插入。
2. 在「离线语音 · 模型与权限」只导入准备试用的引擎模型，明确选择引擎，再允许麦克风。可以仅安装 VoiceKey 测试独立准备路径；另一引擎模型缺席不应拦截。
3. 返回普通输入框，完成已有拼音组字。长按空格或选择「工具 → 离线语音」，讲话时观察预览；点击「停止录音」等待定稿，再点「确认插入」。确认前不自动上屏，最终最多插入一次；可取消或重新录音。没有文字时不能确认插入。
4. 先用非敏感测试文字验证移动光标、切换输入框/应用、隐藏键盘后的取消保护；结果不能写入新位置。密码及禁止学习字段不开放语音。
5. 模型准备完成后关闭 Wi-Fi 和移动数据，重复录音、预览、停止、重录与确认。推理在本机进行，没有云识别回退；录音默认不保存、不上传。网络故障不得阻塞普通输入。
6. 按 [S25 集中验收单](S25_DUAL_VOICE_CHECKLIST.md)一次完成权限、蓝牙、长句、取消、模型故障、触感/主题和输入回归，统一记录失败；不在个人设备运行仓库中限定临时 GitHub 模拟器的 CI 脚本。

CPU固定合成句目前speech-v1 CER为15/22，错误完整保留，不代表自然人声表现，不能据此推荐VoiceKey；X-ASR继续默认。

当前预览为只读。识别错误可重录，或确认插入后在正文中修改。不能用模拟器静音、合成音单句、RTF 或 `100%-CER` 作为自然人声准确率及手机端到端延迟结论。同机比较应分别记录模型校验、冷/热加载、首个非空预览、停止到定稿、CER、峰值内存、CPU、能耗和失败样例；样本数及 P50/P95 的口径见验收单。

遇到问题时优先记录源码、APK SHA、Android/One UI 版本、引擎 ID、模型 ID/SHA、操作顺序和错误类型。不要提交个人剪贴板、账号凭据、录音或聊天正文。三星 S25、自然人声、实际 OS 撤权、ARM Android 端到端表现和正式签名继续保持 NOT RUN；本包只作为 Alpha 试用候选。
