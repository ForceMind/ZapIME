# ZapIME · 闪电输入法

**一个输入法，连接所有设备。**

面向 Android、iOS、macOS、Windows 的原生中文输入法工程。以离线输入、统一个人数据、自建加密同步与长期可恢复为目标。

## 当前状态

开发中，尚未发布完整产品。已固定 MSIME 共享源码，新增离线 Rust 会话适配与独立 Android 原生键盘；Android 双架构开发包已构建，API 28 基础原生输入已在模拟器验证。已增加共享 SQLite 个人数据、持久上传准备层、认证加密恢复模块，以及 Android 离线管理、候选置顶和明文备份入口。其余三端仍在原生验收中；独立 Go/PostgreSQL 密文服务已通过实际数据库、容器与空库恢复测试，四端云同步、配对及完整恢复体验尚未完成。完整证据见[项目状态](docs/STATUS.md)。

macOS InputMethodKit 和 iOS 原生键盘扩展已有独立宿主及原生构建/桥接证据；构建方式和验证边界见 [Apple 开发宿主](docs/APPLE_NATIVE.md)。Windows 最小 TSF 宿主也已加入源码与原生 CI，验证边界见 [Windows 开发宿主](docs/WINDOWS_NATIVE.md)。仓库按用户授权保持公开，签名与用户私密资料不进入源码。

Android 已有九键/全键、明暗配色、遵循系统的按键反馈、连续退格、常用表情和显式光标/剪贴板编辑。原生界面的布局、实际模拟器截图与验证边界见 [Android UI](docs/design/ANDROID_UI.md)。新增九键与离线语音原型的范围、复现及尚未完成项见 [九键与本地语音](docs/VOICE_INPUT.md)。

键盘内可搜索本机短语、收藏和显式保存的剪贴板，直接插入完整内容，并置顶、转收藏或确认删除。搜索词与聊天正文隔离，密码/禁止学习字段拒绝访问；全部使用本机数据。详见 [本机资料面板](docs/LOCAL_DATA_PANEL.md)。

服务器一键安装、自有服务器中转与 Cloudflare R2 密文存储见 [服务器部署](docs/SERVER_DEPLOY.md)。客户端同步接入仍未完成。

## 开发

```sh
git submodule update --init
cargo test --workspace --locked
```

[构建说明](docs/BUILD.md) · [开发计划](docs/DEVELOPMENT_PLAN.md) · [架构](docs/ARCHITECTURE.md) · [接管指令](docs/WORK_HANDOFF_PROMPT.md)

## 数据与隐私原则

本地输入不等网络。个人词库、自定义候选、短语、永久收藏与临时剪贴板使用可迁移的数据体系。云同步计划采用用户自持恢复材料的端到端加密；服务端可替换，四端均保留离线副本。当前 Android 验证包不申请网络权限。

## 许可与来源

客户端采用 GPL-3.0-only，保留 [MSIME](https://github.com/metasequoiaime/msime) 版权及第三方许可。上游固定在 `vendor/msime` 子模块；[来源锁](third_party/upstream-lock.json)、[许可审计](docs/LICENSE_AUDIT.md) 与 [NOTICE](third_party/NOTICE.txt) 记录各组件范围。分发二进制须履行对应源码等义务。未将授权不明确的 msime-cloud 实现复制进项目。

zapime.com / zapime.app 仍是候选域名，未确认购买或绑定。

独立服务源码、启动和备份说明见 [services/sync](services/sync/README.md)，协议见 [SYNC_PROTOCOL.md](docs/SYNC_PROTOCOL.md)。当前尚未连接四端客户端，不代表已部署云服务。
