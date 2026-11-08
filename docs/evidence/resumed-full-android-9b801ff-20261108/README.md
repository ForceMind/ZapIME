# 新固定9b源码的完整Android回归：六作业通过

workflow `42513949678` attempt1已最终 **6/6 SUCCESS**。固定head `9b801ff947486f5418e9ddc84a88941d1cf3eda6`，实际checkout `436b267cbbc5885ecdf58a7515ef0323fbb6f5e8`，二者Git tree均为 `3995757340519d1b21fc5dfb3b2b89367673d087`。完整producer原decoded日志与Git API身份保留；没有重试。

这仅验收该完整普通工作流，不覆盖同源码候选配置实验的 `fresh_superseded` 失败，也不覆盖候选API35的SDK image安装失败。后续产品修订、手机/ARM、自然语音和正式签名仍分别待验，不可用本次正向结果代填。

| 端/作业 | 固定原件复核结果 |
| --- | --- |
| API28 common `128832759250` | 包含两轮真实系统键盘输入/实际服务退出；65项JNI、本机同步/备份边界、真实voice UI、双栈规则及清理均按同源报告器复验通过。 |
| API35 `128836537125` | 同上，两轮真实键盘与65项通过。 |
| API36 `128836537115` | 同上，两轮真实键盘与65项通过。 |
| VoiceKey28 `128836537106` | 14模型+23commit、5边界、watchdog、系统IME录音/预览/取消/停止、共享文档与设置恢复、真实服务退出及双栈规则清理通过。 |
| VoiceKey35 `128836537262` | 同上，所有固定报告严格复验通过。 |
| VoiceKey36 `128836537180` | 同上，所有固定报告严格复验通过。 |

`strict-validation.json`保存逐端结果。模型、commit、权限、boundary、watchdog及sync-preparation报告均调用当前固定源码中的验证器；普通键盘两轮、实际Editable/系统IME恢复/服务退出/ANR与fatal还按原证据中的无副作用辅助函数检查，没有调用带旧SOURCE常量的历史main，也没有借用旧运行的输入结果。

## 原始下载与安全副本

已实际完整下载并核验六份原artifact ZIP的GitHub大小/SHA，逐成员原字节SHA见 `archive-members.json`。三个普通端原包包含合成PCM；common原包还包含两个APK和X-ASR模型ZIP，不能进入源码或本目录。因此：

- 三个 `*-safe-reports.zip` 是**明确声明的派生安全容器**，只保留JSON/TXT/LOG/PNG报告与合成截图。每个保留成员都逐字节等于对应原ZIP成员，原路径保持；只重封ZIP元数据。原ZIP大小/SHA及排除成员路径/大小/SHA全部保留，没有把派生ZIP称为完整原artifact。
- 三个 `full-voicekey-*-original.zip` 均为完整原ZIP，已检查全部23成员都是安全报告/日志/合成截图，不含APK、模型或PCM。
- 所有原ZIP留在scratch复验目录，任何APK、模型ZIP和PCM都没有进入Git。未下载另129MB test-harness，不声称独立验证该附件。
- 原producer日志按UTF8 decoded字节保真gzip（mtime0）；`apk-member-verification.json`记录从common原包实际流式hash的两APK，均与**同一job**打印的构建SHA匹配。这不意味着稳定签名、覆盖升级兼容或和另一个workflow的开发APK逐字节相同。

派生ZIP已用原source-only `check_zip` 检查通过，未修改打包检查器或白名单。`files-manifest.json`只覆盖本目录安全文件，原大包/PCM排除声明和成员hash不被称为重新上传载荷。

本结果使用临时GitHub模拟器、专用合成数据与受保护测试边界。源Alpha标签/三个公开资产未变更；没有操作用户设备或生产，没有新公开二进制发布声明。focused workflow的8项结果另见独立固定源证据；candidate整轮仍应按其实际失败记录判断。
