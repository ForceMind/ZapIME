# 本机密钥准备与系统恢复文件：三 API 实际通过

固定源码 `931d30ec478288e910f9c0d884fa5e9024c88f61`，[focused run42349924323](https://github.com/ForceMind/ZapIME/actions/runs/42349924323)。本目录保存实际下载的三个local-sync-preparation metadata ZIP原件与完整原job日志。它不证明后续客户端传输或服务端新切片。

| API | Job | Artifact | ZIP bytes | 实算 SHA256 | 导航遮挡/关闭 |
| --- | --- | --- | ---: | --- | --- |
| 28 | 128336241049 | 13565835525 | 10720 | `b3495402414729f55ff616cccf2c3c6b0212fb4cbb20191be8ad5966f31d5dd9` | false/0 |
| 35 | 128336241091 | 13565850505 | 30856 | `35c2f97bdd15ba550e82dd37db458ca024513bb5d1e82b67837e44079c1e9d48` | true/1 |
| 36 | 128336241095 | 13565221647 | 18333 | `ceb10a48b8671029516c856b26a628c24b06c1ca6cb4f371ce4cfa00dc8cce1f` | false/0 |

每个ZIP的实际大小/SHA与GitHub digest、run/source相符。以git show冻结的该源码validator逐一严格重验：每端native11项、UI10项全部通过，所有cleanup=true，精确产品UID的IPv4/IPv6 REJECT与removed=true。validator及其FINGER helper SHA见verification.json。没有借旧成功覆盖新代码。

UI10项包括生产按钮与可见确认、真实ACTION_CREATE_DOCUMENT导出、实际JNI恢复同根材料、恢复码核对、唯一文件稳定几何一次FINGER选择及实际ACTION_OPEN_DOCUMENT返回、正常导入保持原材料、缺材料恢复、Activity结束前删除测试文档、退出清除页面秘密。程序化合成EditText输入单独注明，不冒充物理键入或人类备份保管。

API35实际观察到导航抽屉覆盖文件点，保存nav-before原PNG，执行一次公开Back，等待指定roots导航区域消失后再新鲜确认无遮挡几何，最后只触摸文件一次；原PNG已人工查看，前后对应抽屉显示/关闭。API28和API36本轮未观察到该遮挡，关闭次数0；API36本轮不能写成执行过关闭路径。三端provider_selection_unoccluded=true，范围只限已识别DocumentsUI roots导航，不泛称可检测所有遮挡。

旧5b2的API35/36真实失败仍在相邻saf-ui-42347662267目录；当时全套native通过不等于UI通过。修补只改测试驱动/新增校验和证据，不降低原模型、provider、时限、清理或生产事务断言。

**范围限制：** 没有HTTP或云端密钥登记、个人数据上传/下载、四端同步、S25/自然语音或正式签名。根材料、恢复码、模型、音频和APK不在本目录；截图只拍系统提供程序的公开合成文件列表，未拍恢复码页面。原job日志保存GitHub API返回的已解码UTF-8文字；inventory固定所有其他文件字节和SHA，元数据/校验摘要不是原artifact成员。
