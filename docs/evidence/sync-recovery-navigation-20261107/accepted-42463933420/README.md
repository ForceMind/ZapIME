# 导航修正后的三API真实恢复验收

[PR #94](https://github.com/ForceMind/ZapIME/pull/94) 的
[focused42463933420](https://github.com/ForceMind/ZapIME/actions/runs/42463933420)
attempt1实际完成，8个job全部SUCCESS。固定head
`7c2ab398eeb5849092d46a3f9d72d85743762c93`；build metadata原ZIP实际下载核SHA，
实际checkout `ff1f81443102a3dfcc6be2defbabe0e3f244b650`，Git commit API核对
tree `2fee68ef8694ca2b3fc9838cfe4cd57f5f81d032` 与head相同，parents为
main `5a0dc562` 和该head。不能用后续PR更新覆盖本次源码身份。

## 实际下载与严格复验

下列完整原ZIP已实际下载，大小与GitHub SHA256完全匹配，保留为各目录
`original.zip`。逐成员哈希在[archive-manifest.json](archive-manifest.json)，
可读JSON/仪表化与ZIP原字节逐一相同。只包含专用合成报告、系统提供程序公开
文件列表图像、有限活动错误与UID规则，无恢复文件、恢复码或通用敏感页面转储。

| API | artifact | ZIP字节 | SHA256 |
| --- | --- | --- | --- |
| 28 | 13610029928 | 22,224 | `a6dcc2b4dbcb67a6373e34ddb4c256101d0ca76a6f8813627fb61072c80f77c4` |
| 35 | 13611291017 | 33,140 | `f6e264be2451b74245b5c27a45b43fe19071ee634ce4a5f9b58442f8bdfdb23d` |
| 36 | 13611411740 | 31,977 | `e2177c935ba478ddc743969c8944add196d70aaa3a33933e1f9a70bda7b914ae` |

三端分别用当前固定脚本严格复验准备11、恢复UI10、密钥登记14、传输JNI12、
页面生命周期10项，合计每端57项/总171项。三端本轮都实际观察到导航覆盖，
在已识别root后代中执行一次正常Downloads根控件操作；最终导航不在、文件
位置不被导航覆盖，只发送一次目标文件手指down/up。实际系统返回、文件逐字节
相同、native同根、已有材料不变、缺材料原密钥恢复、结束前删本轮文档与清理
全部通过，crash为空。详见[strict-validation.json](strict-validation.json)和原报告。
这些事实证明本修订的恢复路径通过，不将正常导航ACK当作恢复成功，也不宣称
解释了平台所有BACK行为或消除了其他来源的SAF失败。

`build-metadata.zip`为667字节，SHA256
`b7852f2dc50815b41ddd53c94451f2e085a8b49c1c2f57b6aca0b185cd769e87`，
其中[原build成员](voicekey-focused-build.json)记录实际产品/harness输入摘要。
本目录未另下载产品APK或其余focused作业artifact，不把metadata成功冒充独立
重验了所有语音/HTTPS附件。第一次并行读取API35的connector返回未提供文件引用，
改为顺序只读下载后成功；没有重跑工作流或设备。

## 独立全量Android仍失败

同head的[fullAndroid42463933444](https://github.com/ForceMind/ZapIME/actions/runs/42463933444)
attempt1为FAILURE，不能由上述focused成功替换。
build job128678992661在获取固定X-ASR导入模型时收到
`urllib.error.HTTPError: HTTP Error 504: Gateway Time-out`；失败步骤为
`Build checksum-verified offline import model`。后续5个job在输入产物验证阶段失败，
没有把它们称作本次全量模拟器执行成功。

[完整安全build日志](full-android-build-failure.log.gz)保留GitHub connector返回的
完整UTF8解码文本（含BOM），未发现私钥、GitHub凭据或带签名查询的临时URL。
解压289,250字节/SHA256
`0b231b50d35b96d3c6240ea75fd53c98ef05b4117b9d7608ac0461e302e10528`，
gzip mtime0为49,602字节/SHA256
`bbac1533ed4c94bc25ca51f4173018fb6ef1b8bcff0b3028c34bc7b70c0e059d`。
这是API解码文本与归档gzip的摘要，不是GitHub日志ZIP二进制摘要。
[全量失败元数据](full-android-failure-readback.json)保留6个job及其步骤结果，
不重试、不覆盖原失败。

focused八项最终元数据见[workflow-readback.json](workflow-readback.json)，
所有本目录文件摘要见[files-manifest.json](files-manifest.json)。
原870fd5c的API28失败及API35/36成功继续保留在[前序原件](../failure-42459710009/readback.json)。
本目录仅证据，不修改产品/测试/workflow；临时x86_64模拟器结果不替代ARM/S25、
自然输入、正式签名或通用SAF稳定性验收。
