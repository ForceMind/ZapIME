# 无样本与全零PCM边界验收

本目录区分主机策略、Java编译和实际JNI/系统输入。首候选源`4db849ca34dc54962e9e94c63746fa3e67424dcc`（PR #57，Issue #56）的五工作流与Android API28/35/36均完成success，三个精确作业摘录均显示新六类JNI、原中文识别、语音UI的`empty_result_insert_checked=true`以及真实服务退出通过。

## 首候选原件

实际完整下载API28 artifact12736190208，233,582,582字节，SHA256 `2f64a7e6efb52399f3ae64ad6f5bd51bd4092155ce7242328d5cf37380c9b8fc`与GitHub公布digest一致。`initial-api-28/`的11个原文件按原字节保存，含1143B的`empty-pcm-report.json`、原中文报告、native/UI结果、系统IME切换退出、crash与活动错误、宿主trace及未修改的320×640停止截图。截图已实际查看，显示空结果与禁用的确认按钮；行为证明来自真实Editable断言与成功stream，不只凭图片。

六类真实JNI/固定模型会话为：无样本、3200个全零采样、count外非零、正负1LSB和取消空会话。报告只含固定ID/布尔/样本数/通过项；正负1LSB都保留非零资格，取消不能被空结果快速结束绕过。重复finish与结束后feed拒绝，原合成中文断言及600块样本上限/取消继续通过。

`initial-api35-success.log`和`initial-api36-success.log`是对应原始job精确选段，不声称已下载它们的首候选ZIP。API28整ZIP和ARM64 APK/模型的实际本地校验见`package-verification.json`；模型ZIP六成员SHA与APK固定配置一致，GPL/语音许可资产存在，权重不在产品APK。二进制、模型及完整ZIP只留ignored target/CI，未进Git。

首候选将新边界测试放在原合成计时之前，故其`recognizer_load_ms`是已预热模型的会话创建，不能当冷模型加载或性能提升。后续`59c9e8e17741250c8783bbc108efc08ad8b87edb`仅将新测试移到原计时/报告之后并明确计时范围；新首个空会话也接在真实非零识别后，直接验证会话隔离。此改序源的设备结果尚待实际完成，不借首候选五绿冒充。

## 已有本地证据

`local-cpp-policy.json`实际编译/运行生产策略头与独立C++合成测试，包括模拟非空模型文本屏蔽、有效前缀、±1/int16极值、尾静音和会话隔离；这是主机策略，未调用JNI或模型。`local-java-test-compile.json`保存首候选两组Java编译（target 2源4class，独立test-host 2源11class）；`local-java-timing-order-compile.json`保存改序后target 2源4class复编译。均使用JDK17/官方固定API36 jar，源码SHA前后相同，不称APK/设备验收。

规则只判断成功接受的PCM16前缀是否曾非零，不设能量/VAD阈值，不证明真人讲话、系统静音或权限状态，不更换模型或声称自然语音识别率提高。原权限/配置回调、取消、焦点及确认位置检查保持独立；自然语音、ARM/三星、正式升级和四端语音仍未验收。
