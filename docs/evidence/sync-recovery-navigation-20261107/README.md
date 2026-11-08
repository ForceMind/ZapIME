# API28 同步恢复：已观察的系统导航修复

客户端日期2026-11-07。冻结原失败提交为 `18b12aee22c57a4979c96f551f9200b45b057185`，
在修改导航之前保留原件。基线 `f6f69d6b1fcd146d0590e68cd5847ca25782fd91`
包含前一诊断PR及main证据；不是回退或覆盖已验的 `870fd5c7`。

## 新诊断真实复现

[focused42459710009 / API28 job128667965894](https://github.com/ForceMind/ZapIME/actions/runs/42459710009/job/128667965894)
再次在 `actual SAF open` 失败。artifact13610635973实际下载12,034字节，
SHA256 `4f4f2c304c41db54750f0b3f3283373ed55b830ae6d44040f6dddf7f91f710bf`
与GitHub一致；[原安全报告](failure-42459710009/api28/sync-preparation-ui-report.json)
通过固定诊断解析，完整恢复门禁仍严格拒绝。

- 阶段 `navigation_close_wait`、窗口 `documents_ui`，导航节点仍在，目标文件名节点已不在。
- 观察150轮、请求一次关闭、文件触摸0次。尚未进入产品选择结果或恢复导入。
- 前序创建/导出/native同根/码核对通过；准备11、key14、transfer12通过，pages未运行。
- 清理完成，crash为空。不能把前序成功或清理改写成恢复通过。

同run的API35/36分别严格复验57项通过。ZIP也实际下载核SHA，
API35 artifact13609556485为23,057字节/SHA
`2d0e7b80e58f0fd534154f923b854326234aff8573183c8a84129844f3dfd300`，
API36 artifact13609760847为33,775字节/SHA
`c3bc0e45925d11169f0130d817115324986a635d07767cab0746f707189e7e00`。
API36观察覆盖并完成关闭，API35未观察覆盖，均只有一次文件物理触摸。
成功没有解释或消除API28原失败。

build metadata下载669字节/SHA
`cb0a1360a6fd759f453333b490fc80a916f19763e7cfc05bd0787ce8ad73547c`，
实际checkout `f6e1a016bd415e28809a2b6f8e66a8395c85b447`，
tree `9644f18173fdb5d344c92021cea8755aad1ede65` 与run head870fd5c树相同。
Git commit API另核对其parents为main `be5b4563` 和该head。
逐成员原件SHA见[manifest](failure-42459710009/manifest.json)，
[readback](failure-42459710009/readback.json)是生成的来源摘要；没有保留ZIP、
恢复文件、秘密或截图。`build.json`保留下载的原始元数据成员字节。

## 修复依据与范围

公开AOSP固定标签 `android-9.0.0_r33` 的
[SharedInputHandler.onBack](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-9.0.0_r33/src/com/android/documentsui/SharedInputHandler.java)
处理搜索、选择和目录返回，不能将其发送成功视为抽屉已关闭。
[RootsFragment正常条目点击](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-9.0.0_r33/src/com/android/documentsui/sidebar/RootsFragment.java)
在打开所选root后显式请求关闭抽屉。
这是选择普通UI导航的实现依据，与本次观察相符；未证明模拟器所装APK
与该标签逐字节相同，也没有确证平台唯一根因。

新test-only驱动只在识别的导航节点后代树中找唯一、可见、启用的Downloads根条目，
向上寻找可点击祖先时必须在该导航节点前停止，限制其矩形也在导航范围内且不是整个导航容器，重新获取后只执行一次普通
ACTION_CLICK。没有盲BACK、文件ACTION_CLICK回退、跨进程API调用或新权限。
后续继续等具名文件出现并稳定，导航节点本身存在不等于覆盖：只在节点矩形
包含文件触摸点时视为遮挡。最终重新取文件和导航几何，在点位不受该导航覆盖时
才执行原来的唯一手指down/up；单次ACK不代替实际系统返回、逐字节文件比对、
native同根恢复、正常确认、缺材料恢复及清理门禁。

新schema2记录root动作/范围及最终导航存在性、点位遮挡证明。
schema1原证据继续按原规则验证，不改原件。新状态机拒绝重复root或文件触摸；
报告拒绝第二次动作、旧BACK方法、越界/非整数几何、未清覆盖和最终点位遮挡。
仍仅检查已识别DocumentsUI导航，不宣称通用overlay检测、用户手势或手机验收。
为兼容原失败诊断，`navigation_close_attempts`字段继续沿用；新修订中它计的是
一次根控件导航请求，不代表已经观察到整个抽屉关闭。schema2另存实际root动作
和最终文件点位是否脱离导航范围。

## 本机验证与尚待验收

102项Android Python（其中9项准备/恢复报告）、两组导航/trace JVM，以及全部
voice-tests Java（官方API36r02、Temurin17.0.21、source8/target8）通过。
历史API35/36原PASS仍通过新validator，API28原FAIL仍拒绝；未放宽任何失败为通过。
源码hash见[validation.json](validation.json)。新三API模拟器运行尚待本修订CI，
不拿前一源码的两个成功代填。产品实现、密钥、权限、网络和workflow均未修改。
独立复审发现的“仅按矩形无法证明导航后代归属”已修正并再次复审通过，
未通过放宽目标文件或恢复断言处理。
