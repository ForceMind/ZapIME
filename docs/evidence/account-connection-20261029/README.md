# 设备身份连接候选验证

基线main408f58c0e6d0ba441cb8027157084aabf07b4c2a；Issue #62。生产增加只读会话接口、Android显式连接/检查/移除页面、严格身份验证和独立连接Keystore域，不接个人资料上传、同步开关或E2EE密钥交换。

`local-java-compile.json`记录固定官方API36 jar与JDK17的实际产品/target源码编译，源码SHA前后复核；仅Java，不等于APK或模拟器。`local-tls-report.json`为实际主机JVM HTTPS回环与生产SyncHttp的报告，验证信任/不信任CA、主机名、跳转、限量/读取预算、取消和不应用层重试；测试CA只在该测试JVM配置，没有进入产品。它不是Android TLS或用户服务器部署。

本地17项Android工具/边界单测通过，其中新UID防火墙守卫/清理使用模拟子进程，不冒充内核隔离；实际三API必须写出两族拒绝及清理证明。Go本地测试/race/vet已执行，缺少测试数据库的Session集成项明确SKIP，不能记作真实PostgreSQL通过；本轮CI要求真实数据库。

待执行：三API实际Keystore连接/material交叉替换拒绝与移除不破坏材料、严格JSON/身份匹配、本机存储与敏感Activity生命周期；原语音/两轮输入/真实服务退出保留，并在受保护CI用精确产品UID的IPv4/IPv6拒绝规则执行。报告只含固定case/通过项，无凭据或用户正文。主机TLS、本机探针和实际手机/服务器验证分别记录，不借上轮7a8667a五绿预记本次成功。
