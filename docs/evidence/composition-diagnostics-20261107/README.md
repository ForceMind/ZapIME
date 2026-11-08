# 组字事件诊断本机验证

基线 `58a3a546c758378d1887aeeaa99b4bd4d3a25c77`。本次仅增加固定枚举与计数的有界诊断及独立测试宿主证据，不改变组字、排序、候选点击、取消或门禁判断。

[验证清单及源码摘要](validation.json)记录两组 JVM、14 项 Python 和完整 58 个产品 / 3 个独立宿主 Java 源文件编译通过。首次缺少 javac 的未运行也保留；随后使用已核验的 Temurin17 与官方 API36r02 jar 完成验证。Java8 source/target 编译保留 bootstrap/deprecated 警告，不将其包装为 APK 或系统 IME 执行证据。

[设计与历史失败分析](../../ANDROID_COMPOSITION_DIAGNOSTICS.md)。原 API35 失败仍未确定唯一根因；本切片的新 Android CI 尚待运行，不能借旧成功代填。

## 上层 epoch 拒绝观察补充

审查发现 `send` 与个人资料回调可在进入 `deliverInputReply` 之前被旧 epoch 拒绝，原环未能观察该路径。新增纯计数观察器在原准入表达式前记录输入/搜索与个人资料两种固定事件，不改变准入或读取回复正文。相同 epoch 不记事件，旧 epoch 两种事件独立验证。[补充验证及源码摘要](epoch-observer-validation.json)。真实键日志 `attempt` 明确发生在注入前，不当成注入 ACK；其他未覆盖路径在设计说明中逐项限定。两组 JVM 与 58+3 Java 编译重新通过，新的系统 IME CI 仍待执行。
