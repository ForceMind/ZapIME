# 架构与当前实现

## 已实现的输入链路

Android InputMethodService → 单线程后台队列 → JNI → zapime-core → MSIME Session → Rust 拼音引擎。UI 主线程只负责按键收集、候选显示与 InputConnection 上屏；首次词库安装与会话准备在后台串行执行。每个输入框有 epoch，失焦后的旧结果不提交到新输入框。每个候选快照有 generation，旧候选点击不提交。

词库随包携带、构建时验证 SHA-256。应用没有 INTERNET 权限。资源、个人数据和缓存是三个独立目录，个人词库的工作副本由上游 generation 机制准备。当前候选列表最多显示前 40 条，完整分页仍待迁移。

## 上游与扩展边界

vendor/msime 为精确锁定的完整源码，内含 engine、input-runtime、host-api 与四端原生宿主。当前链接 engine 和 path-trust，尚未迁移完整 host-api 服务图。根目录 crates 为 ZapIME 自有适配、SQLite 个人数据与认证加密/恢复模块。Android 原生管理页通过同一后台 JNI 会话读取、编辑与导入导出个人资料；输入会话缓存有效规则并按精确编码应用。

## 后续链路

共享本地数据（SQLite、版本化归档、离线操作）→ 后台同步客户端（E2EE）→ 独立 Go/PostgreSQL 服务。客户端保存可迁移副本与密钥恢复资料。服务端不可接触明文正文。永久收藏与临时剪贴板分表及分生命周期。详见开发计划；此链路尚未完成。

## 其余平台

iOS UIInputViewController + 主 App、macOS InputMethodKit、Windows TSF DLL/Server 保留原生系统集成设计，迁移前先更新身份与数据路径。不能直接把 submodule 的上游品牌安装包视为 ZapIME 产品。
