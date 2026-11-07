# Android 手动同步客户端：固定源码三 API 离线边界与恢复 UI 验收

PR75 head `e3c4da10b5f90917c2c8d17aeb8fc382f78ec4f9`，实际CI checkout `2c61712f9414f40f66017aeb1448642c1f5abb89`，两者Git tree均 `d242254a6c62f02ed3b4b10a04086a0708e15845`（Git API独立核对）。[focused run42351436372](https://github.com/ForceMind/ZapIME/actions/runs/42351436372) 的三个local-sync-preparation作业已SUCCESS；本目录是实际下载的原报告/截图/日志与额外校验摘要。

| API | Job | Artifact | ZIP bytes | 实算 ZIP SHA256 | 47项严格重验 | 导航遮挡/关闭 |
| --- | --- | --- | ---: | --- | --- | --- |
| 28 | 128340904559 | 13566330057 | 12610 | `24ad763f82bd05914f22be61310cf1e7ac09053a85057236be3699e539bf9b78` | PASS | false/0 |
| 35 | 128340904517 | 13566280175 | 22070 | `df1d9ae91814aab7ac2420a9cb21a8ad52f8cf6a875fc0adbd64db3c7d0642dc` | PASS | false/0 |
| 36 | 128340904744 | 13565303361 | 32713 | `d4b5e37d845cf91150533847c8bbf67409c4ad71566d04f22d0cebff444a4146` | PASS | true/1 |

每端以git show固定head源码的validator验14项key descriptor+12项真实JNI transfer+11项准备+10项实际SAF UI，总计47项。冻结了三个报告validator及其完整本地导入依赖，源码尺寸/SHA见verification.json；没有用后来代码的validator回套。每个原ZIP的大小/SHA、GitHub digest、run和head均核对。输入build metadata原包也实际重算摘要，原JSON记录实际checkout为synthetic PR merge，与head同树，不误报为不同产品源码。

新增14项：严格descriptor/响应解析、真实Native descriptor摘要、真实Keystore准备及worker/缺连接/未准备/预取消前置边界；报告明确Android HTTPS与server CAS为NOT RUN。新增12项：真实JNI编辑、持久冻结密文重试、严格ACK与身份/修订拒绝、ACK不推进拉取cursor且保留后续编辑、真实认证解密/接收端无回声、坏第二条全页回滚及失败密文保存、错误材料账号与资源边界。只有服务端外层ACK/page元数据为合成，密文由真实native产生、认证和存储；没有模拟识别、假译文或把合成外层当真实HTTPS。

原11项准备与10项系统文件恢复UI继续通过。原生/descriptor/transfer/UI各自cleanup均true，精确产品UID的IPv4/IPv6 REJECT及removed=true。API36本轮观察导航遮挡并关闭一次；API28/35未观察到遮挡，关闭0次。各端一次新鲜稳定文件FINGER选择、实际provider返回和文档删除均验证；不泛称能识别所有遮挡。

**限制：** 尚未验证Android通过HTTPS连接真实服务器的整轮交换、两台Android端到端、配对、真实R2、手机/自然语音及正式签名。这些成功不能替后续词库缓存/其他提交背书。用户的根材料、恢复码、正文、模型、音频和APK不在本目录；只保留计数/布尔/公共metadata与provider合成文件列表截图。

raw成员直接从三个ZIP提取，job日志保存GitHub返回的已解码UTF-8文字。ZIP和冻结源码副本仅保留ignored target，本目录的inventory只覆盖用于入库的原件与摘要，避免把模型或构建包混入证据。
