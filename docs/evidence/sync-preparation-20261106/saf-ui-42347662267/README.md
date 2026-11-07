# 系统密钥恢复文件 UI 首轮：API28通过，API35/36保留真实失败

源码 `5b2d9488fbd0eecd6e4be63d8f8ac0840fe838ba`，[focused run42347662267](https://github.com/ForceMind/ZapIME/actions/runs/42347662267)。本目录是实际下载的三个 local-sync-preparation metadata ZIP 与完整原作业日志，不包含模型、音频、APK、密钥或恢复码。

| API | Job | Artifact | ZIP bytes | 实算 SHA256 | native 11项 | UI 10项 |
| --- | --- | --- | ---: | --- | --- | --- |
| 28 | 128331073967 | 13564273197 | 11248 | `0e9da1e2efe138d309dfebf74f07acd2af5e7e8f74dabe30fd9e84ec6af935ce` | PASS | PASS |
| 35 | 128331074027 | 13564941009 | 13428 | `9babaf0de9e42df9328875a22c01288a380842790e05d8d0fcc3de73eeae1cf5` | PASS | FAIL |
| 36 | 128331073980 | 13564726439 | 13316 | `fc917ca1cc24a97e87d0117b4297d4ce92d575f6f767c1e21d845a8cbe2e28cd` | PASS | FAIL |

三个ZIP的实际大小/SHA、GitHub digest、run与源码均匹配。历史 `5b2d9488` 的 `scripts/android_sync_preparation_report.py` 通过 `git show` 冻结到 ignored target 后加载，原版 `verify_report` 严格验证各端11项及cleanup；原版 `verify_ui_report` 对API28通过、API35/36按其原始失败明确拒绝。依赖的FINGER校验helper与该源码逐字节相同；两个源文件SHA记录于verification.json。后来的“无遮挡”新字段不回套历史报告，不能将历史API28成功改称失败。

API35/36均已完成：新鲜资料库、正常按钮及确认对话框创建、真实ACTION_CREATE_DOCUMENT导出、真实JNI恢复同根材料、恢复码核对。随后在 `actual SAF open` 失败（IOException）；原instrumentation仅报告该阶段，未打印更深cause，不补造异常文字。两端actual_provider_open=false；没有运行后面的正常导入/缺材料UI等成功断言。

原报告两端均记录一次FINGER、点位 `[109,381]`、文件名bounds `[74,372,144,391]`。`sync-key-provider-before.png` 原件保留，用于核对文件点被导航抽屉覆盖的现场；inject ACK不等于提供程序真正返回。后续应修正驱动对遮挡的观察与关闭，再执行新源码，不能盲重试文件触摸或放宽provider退出断言。

API28完整10项通过，包含真实文件导出/打开、固定同根恢复、已有材料不替换、缺文件恢复UI、提供程序文档在Activity结束前删除。其控件使用生产View.performClick与可见确认对话框；恢复码输入为合成程序化EditText，不称物理键入或真人保管验收。

三端原生11项均通过、native/UI的cleanup均true、文档删除true；offline-uid另核精确产品包名/UID、IPv4/IPv6 REJECT与removed=true。原失败没有删除，清理成功也不把UI失败变为通过。新驱动和后续服务器/客户端WIP必须各自运行验证。

raw原件由ZIP逐成员直接提取，job日志保存GitHub返回的已解码文字UTF-8字节；inventory记录本目录所有其他文件的大小/SHA。元数据和校验摘要为额外生成的证据，不冒充原artifact成员。
