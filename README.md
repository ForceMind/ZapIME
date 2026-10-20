# ZapIME · 闪电输入法

**一个输入法，连接所有设备。**

面向 Android、iOS、macOS、Windows 的原生中文输入法工程。以离线输入、统一个人数据、自建加密同步与长期可恢复为目标。

## 当前状态

开发中，尚未发布完整产品。已固定 MSIME 共享源码，新增离线 Rust 会话适配与独立 Android 原生键盘；输入集成测试已运行，Android 打包验证进行中。已增加共享 SQLite 个人数据、认证加密恢复模块，以及 Android 离线管理、候选置顶和明文备份入口。其余三端、云服务、配对及完整恢复体验尚未完成。完整证据见[项目状态](docs/STATUS.md)。

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
