# 08e84980 focused 输入 · 热修复包实际核验

源码 `08e84980562ecc8fab436ffcdab6e5aee8c567e7`，focused run `42221304313`，artifact `13503044367`。完整ZIP实际52904248字节，SHA256 `b50bea87a9cda338dbfbc7d1fc1919919bdfeaf44ff2d45e3dde6137f0e4b815` 与GitHub metadata一致。此focused输入只有x86_64产品、两个harness、固定synthetic WAV，不含ARM APK或模型包。

| 输入 | 字节 | SHA256 |
| --- | --- | --- |
| x86_64 产品 | 53108903 | `fb5d3636c8b84c8ba036577e75d0dcdb96f86d3ed22bfba15a21c193d1b64548` |
| editor harness | 45556 | `19bc5ceb37186ff9e018a920843d17b00371c410bb1e5f0e9aeae51244d53a88` |
| VoiceKey-only harness | 340612 | `ef9e86666f93c3f8c90928ffb3245e524571f2d70c50490e482e9d42b96d8f3b` |

产品实际通过原check_apk完整门禁：32包独立许可、精确源码锁SHA、8JNI导出、动态依赖白名单、五库ELF/16KiB、manifest、apksigner35、zipalign。额外从实际DEX解析8个native声明，确认新 `finish(JI)[B` 与非native Java wrapper `finish(J)[B` 同时存在；真实旧C7 DEX则只有一参native签名，不能冒充热修复包。这证明声明契约，不替代JNI实际调用/非法budget/模型终止的运行验收。

语音harness实际assets严格只有NOTICE、根GPL、synthetic.pcm，PCM与同包WAV音频帧逐字节相等；editor harness仅有GPL资产，无已知模型成员。三个APK均实际签名/zipalign通过，共用开发证书SHA256 `5f24035153f4ee5a633894c315127d6b9a0ccfe3ac71d9d64283a01ab2214063`。它不同于历史runner产物，不承诺跨包覆盖升级，试用前须保存可恢复备份。

该记录不代表安装、实际VoiceKey模型/AudioRecord/Editable、系统IME、ARM执行、自然语音或热修复时限通过；各自运行证据独立判断。仓库仅保存metadata与工具文本，未保存APK/PCM/WAV/模型权重。
