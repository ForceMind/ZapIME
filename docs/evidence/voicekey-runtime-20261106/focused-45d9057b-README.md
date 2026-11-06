# 45d9057b focused 输入 · 实际包与无模型资产核验

源码 `45d9057b19f78f8b35599cfe1c1b19a50f493184`，focused run `42215806243`，artifact `13501058016`。授权下载引用初次 GET 返回503，同一引用重试后成功；完整ZIP实际52896334字节，SHA256 `4a8e8ac3ec7f4f1a1920669662d9362a51c6edc715e80b03586acf9556a81d84` 与GitHub metadata一致。产物只有四个成员：x86_64产品APK、editor harness、VoiceKey-only voice harness、固定synthetic WAV，没有ARM APK或任何模型包。

| 输入 | 字节 | SHA256 |
| --- | --- | --- |
| x86_64 产品 APK | 53108903 | `d84a67bfdef064674e58abbc3094b0a1770d39943b8fb49b6570fd1faf5be829` |
| editor harness APK | 45556 | `ee0f1f974101615ac1f449c7b9183cdb496ba509a86481a9e804628b716fd819` |
| VoiceKey-only harness APK | 332420 | `3748a063736daf47ffc5c296c99b94efce34482a56aa628829f2a46c429e0588` |

产品实际通过固定源码check_apk：32包独立许可和精确Cargo.lock来源、8JNI导出、动态依赖白名单、五库ELF/16KiB、manifest、apksigner35和zipalign检查。核对的产品编译源码路径与C7相同，但本artifact的来源、字节与签名仍单独记录，不能借C7产物替代45的测试源码。

语音harness实际assets严格等于NOTICE、根GPL全文、synthetic.pcm三项；没有assets/voice-fixtures/model.zip、X-ASR/VoiceKey权重或额外资产。PCM为250980字节/125490样本、16kHz单声道PCM16，逐字节等于同ZIP固定WAV的音频帧。editor harness仅含GPL资产。三个APK均实际签名/zipalign通过，并共用开发证书SHA256 `f0677b80515ead8311026fd1473a91686ddb74bb7563561c7a84956d85f92345`，不同于此前runner产物，不承诺直接覆盖升级。

本报告仅是实际下载字节、签名和无模型资产边界，不是安装、SAF、VoiceKey解码、AudioRecord/Editable、系统IME、ARM执行或自然语音验收。具体模型专项需其真实模拟器报告。仓库仅保存metadata和工具文本；未保存APK/WAV/PCM/权重。
