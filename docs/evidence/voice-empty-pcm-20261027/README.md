# 无样本与全零PCM边界验收

本目录区分主机策略、Java编译和实际JNI/系统输入。PR #57 / Issue #56最终源为`59c9e8e17741250c8783bbc108efc08ad8b87edb`，五条工作流及Android API28/35/36均已完成success；代码合并提交为`b2079587e495ab80d869e27ffc1f5f825eb3e6bb`。首候选原件独立保留，不能替代最终源结果。

## 最终源实际结果

| 工作流 | 精确run | 结果 |
| --- | --- | --- |
| Android原生输入 | [40509500246](https://github.com/ForceMind/ZapIME/actions/runs/40509500246) | success，双架构APK；API28 job122207352743、API35 job122213385991、API36 job122213385769 |
| 通用开发检查 | [40509500199](https://github.com/ForceMind/ZapIME/actions/runs/40509500199) | success |
| 独立密文服务 | [40509500276](https://github.com/ForceMind/ZapIME/actions/runs/40509500276) | success |
| 离线语音CPU | [40509500290](https://github.com/ForceMind/ZapIME/actions/runs/40509500290) | success |
| Windows既有基线 | [40509500190](https://github.com/ForceMind/ZapIME/actions/runs/40509500190) | success，不代表#41系统输入已解决 |

三API均实际通过六类JNI、原合成中文识别、两轮独立Editable输入与语音UI；`ui-instrumentation.txt`均明确`empty_result_insert_checked=true`。实际切回核实为系统包的IME、等待ZapIME服务退出及拒绝App fatal均通过。API28为AOSP，API35/36的精确系统组件保存在各`system-keyboard.json`。`final-ci-summary.json`是本次精确源的状态摘录，不把后续main/文档提交的独立运行预记成功。

下列三个ZIP均完整下载、按公布字节数和SHA256校验。每个`final-api-28/35/36/`保存从对应ZIP逐字节选出的11个原文件：两份native报告、native/UI instrumentation、combined gates、系统IME与退出、crash/活动错误、宿主trace和停止截图。API36原图已实际查看，空提示及禁用的确认按钮可见；行为依据仍为真实Editable断言。ZIP/模型/PCM/APK只在ignored target与CI，不进Git。

| 产物ID | 字节数 | SHA256 |
| --- | --- | --- |
| API28 development 12737401857 | 233,569,719 | `0f764b1ceb33688b7d1d7a9aabfe3b199c57824b5d7ee0fab96d949786ec6c8c` |
| API35 12737822222 | 2,433,768 | `8d3ed38dbb3ee8e3337d7f4e37f1c19249b72ca4ee6dd2bfc2e8aa6b196b6835` |
| API36 12738700215 | 2,434,096 | `ddfc2b1efaae8ac1d7467fc4e10106d03a61ab41a9535dd27507ff6e0b4ab9cf` |

`final-package-verification.json`记录最终ARM64 APK/模型大小与SHA、模型六成员和APK固定配置的实际核对，以及GPL/语音许可资产、权重未内置的检查。开发签名/对齐由CI检查；未声称本地重复apksigner验收或ARM真机运行。安装步骤与签名边界见[试用指南](../../ANDROID_TRYOUT.md)。`manifest.json`列出本目录除清单自身外的文件大小、SHA与来源，原件和派生记录分开标识。

## 首候选原件

实际完整下载API28 artifact12736190208，233,582,582字节，SHA256 `2f64a7e6efb52399f3ae64ad6f5bd51bd4092155ce7242328d5cf37380c9b8fc`与GitHub公布digest一致。`initial-api-28/`的11个原文件按原字节保存，含1143B的`empty-pcm-report.json`、原中文报告、native/UI结果、系统IME切换退出、crash与活动错误、宿主trace及未修改的320×640停止截图。截图已实际查看，显示空结果与禁用的确认按钮；行为证明来自真实Editable断言与成功stream，不只凭图片。

六类真实JNI/固定模型会话为：无样本、3200个全零采样、count外非零、正负1LSB和取消空会话。报告只含固定ID/布尔/样本数/通过项；正负1LSB都保留非零资格，取消不能被空结果快速结束绕过。重复finish与结束后feed拒绝，原合成中文断言及600块样本上限/取消继续通过。

`initial-api35-success.log`和`initial-api36-success.log`是对应原始job精确选段，不声称已下载它们的首候选ZIP。API28整ZIP和ARM64 APK/模型的实际本地校验见`package-verification.json`；模型ZIP六成员SHA与APK固定配置一致，GPL/语音许可资产存在，权重不在产品APK。二进制、模型及完整ZIP只留ignored target/CI，未进Git。

首候选源`4db849ca34dc54962e9e94c63746fa3e67424dcc`的五工作流与三API也实际success。它将新边界测试放在原合成计时之前，故其`recognizer_load_ms`是已预热模型的会话创建，不能当冷模型加载或性能提升。最终59c9e8e仅将新测试移到原计时/报告之后并明确计时范围；新首个空会话也接在真实非零识别后，直接验证会话隔离。最终三API原报告均含`recognizer_load_scope`，这里只是校验/类加载后的start(handle)，不含麦克风端到端和手机冷启动；新实际结果见上表。

## 已有本地证据

`local-cpp-policy.json`实际编译/运行生产策略头与独立C++合成测试，包括模拟非空模型文本屏蔽、有效前缀、±1/int16极值、尾静音和会话隔离；这是主机策略，未调用JNI或模型。`local-java-test-compile.json`保存首候选两组Java编译（target 2源4class，独立test-host 2源11class）；`local-java-timing-order-compile.json`保存改序后target 2源4class复编译。均使用JDK17/官方固定API36 jar，源码SHA前后相同，不称APK/设备验收。

规则只判断成功接受的PCM16前缀是否曾非零，不设能量/VAD阈值，不证明真人讲话、系统静音或权限状态，不更换模型或声称自然语音识别率提高。原权限/配置回调、取消、焦点及确认位置检查保持独立；自然语音、ARM/三星、正式升级和四端语音仍未验收。
