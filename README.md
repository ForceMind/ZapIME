# ZapIME · 闪电输入法

**一个输入法，连接所有设备。**

ZapIME 计划打造 Android、iOS、macOS 与 Windows 原生输入法，提供统一的个人词库、自定义候选字词、快捷短语、端到端加密云剪贴板、永久收藏与用户可掌控的自建同步服务。

> **开发状态（2026-10-20）：**工程规划已建立，**尚未引入客户端或服务器源码**。这是新建项目，四端产品尚未构建或发布；不要把设计目标表述为已经实现的功能。

## 开发与工作交接

- [完整产品需求、架构与开发路线图](docs/DEVELOPMENT_PLAN.md)
- [ChatGPT Work 持续自主研发接管指令](docs/WORK_HANDOFF_PROMPT.md)
- [当前执行状态](docs/STATUS.md)

## 核心设计原则

- **四端原生**：Android InputMethodService、iOS Keyboard Extension、macOS InputMethodKit、Windows TSF。
- **离线优先**：基本输入、已保存个人词库及快捷短语不依赖网络。
- **本机即权威副本**：数据可导出和加密恢复；同步服务可替换并自行部署。
- **不混淆剪贴板和永久收藏**：临时内容自动过期不影响用户主动保存的信息。
- **隐私可验证**：敏感输入不默认上传；端到端加密、多设备配对和撤销。
- **授权合规**：保留复用代码应有的开源许可证、版权声明及第三方资源 NOTICE。

## 技术研究基线

- [MSIME / 水杉输入法](https://github.com/metasequoiaime/msime)：跨平台原生输入法及 Rust 共享引擎，**GPL-3.0-only**。必须保留合法授权、版权和 NOTICE；用户不应以为只是改名字即可不受 GPL 约束。
- [chinese-ime-lm](https://github.com/metasequoiaime/chinese-ime-lm)：输入法候选重排模型研究；公开参考推理和权重有 Apache-2.0 授权及语料署名义务。
- [msime-cloud](https://github.com/metasequoiaime/msime-cloud)：Go 服务端研究参考，**不能假设公开可见即已获得二次使用许可**；未确认授权前不直接复制后端实现。

域名 zapime.com、zapime.app 均为候选；**未确认已购得**，任何生产 DNS/API 配置不得擅自以此为前提。

详见计划文档。