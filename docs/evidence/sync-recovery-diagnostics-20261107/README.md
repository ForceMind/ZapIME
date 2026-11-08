# 同步恢复 SAF 失败定位接缝

基线 `58a3a546c758378d1887aeeaa99b4bd4d3a25c77`，客户端日期 2026-11-07。
本切片只改 test-only 仪表化和严格报告解析，没有修改产品、文件权限、
提供程序、恢复身份或单次物理触摸/清理门禁。模拟器新结果尚未运行。

## 原失败的重新核对

重新通过 GitHub connector 在内存读取 [42440405925 / API28 job128609038474](https://github.com/ForceMind/ZapIME/actions/runs/42440405925/job/128609038474)
的 artifact13600749254：12,459字节，SHA256
`c7d7759c6138cac0af34728dffce838ab48b31f160266fb4083657c8a78377dd`。
同[原安全记录](../source-release-ci-20261107/voicekey-first/README.md)一致。
只检查安全的报告、仪表化状态及系统提供程序截图；没有保留ZIP、恢复页面或秘密。

报告已证实导出、native 同根恢复和恢复码确认通过，但 `actual SAF open`
抛出 IOException。`provider_navigation_cover_detected=true`、关闭次数1，
没有 `provider_physical_selection_count` 或 before-touch 截图。
仅有的 nav-before 图像显示系统 DocumentsUI 的真实抽屉覆盖文件列表。
因此失败位于已请求关闭抽屉之后、首次文件物理触摸之前；不能据此断定
BACK 没有关抽屉、旧无障碍节点仍可见、提供程序已退出或文件消失。
原报告只保存异常类型，不能分辨上述情况。清理完成仍不代表恢复通过。

## 新增可验证诊断

`SyncRecoveryTrace` 只保存固定 enum、bool 和有界计数：
选择子阶段（包括提供程序截图IO、关闭请求/等待、触摸和返回）、当前窗口分类（无窗口/系统 DocumentsUI/本产品/其他）、
已识别导航是否存在、合成文件名节点是否存在、最多150次观察、
最多一次关闭请求和一次触摸尝试。异常只在恢复打开阶段保存这些字段。
不保存 Throwable message/cause、正文、节点文字、包名、URI、恢复码或密钥。
在产品窗口只读取窗口包标识用于分类，不遍历该敏感页面。

新 `failure_diagnostic` 只能存在于 FAIL 报告；解析器拒绝未知字段、
任意字符串、强制类型转换、超界次数和矛盾窗口观察，错误输出不回显值。
原有 PASS schema1 报告保持兼容，诊断永远不能使失败报告通过。
只补观察，不在首次 ACK 后重试物理触摸、不延长门槛或忽略导航。

## 本地主机验证与下一步

- 99项 Android Python 回归通过，其中6项准备/恢复报告回归，包含恶意文本
  payload、未知字段、bool/int混淆、超量和 FAIL→PASS 冒用。
- JVM合成路径区分持续导航等待、非预期产品返回、文件缺失与触摸后等待返回；
  计数边界通过。
- 全部 voice-tests Java 源文件用 API36r02 android.jar、Java21.0.13及Temurin17.0.21、
  source8/target8 编译通过。这是编译证据，不能代替 Android 执行。
- 首次本地脚本因 javac 不在 PATH 未运行；选择环境已有 JDK 编译器后通过。
  产品和模拟器未因这个环境问题而变更。

源码哈希与范围见 [validation.json](validation.json)。
focused workflow 已接入这两类主机检查；接续触发三API真实恢复作业，
若再次失败按固定子阶段与窗口/节点观察定位，再决定针对性修复。
没有把其他源码/其他门禁成功改写为本次 SAF 已修复；ARM/S25仍未验收。
