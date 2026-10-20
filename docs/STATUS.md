# ZapIME 项目执行状态

更新时间：2026-10-20 14:00 北京时间；本次 Work 工程记录 #1；之前没有可靠的研发汇报时间。

## 当前事实

- 仓库 Private，基线 main 076961750bab1657b8a98c5e019daa234861a54a，跟踪 Issue #1。
- 已固定 MSIME 完整源码子模块、实现 ZapIME Rust 离线会话适配及原生 Android InputMethodService。不是上游品牌 APK，也不是网页键盘。
- Rust 引擎及适配层已在 Linux 编译；4 项输入集成测试通过。Android arm64 release 原生库已编译通过，APK 打包发现环境缺 javac，正在补齐 JDK。
- 生产拼音/英文资源大小和 SHA-256 已通过验证，manifest 排除了未授权输入；没有复制 msime-cloud 实现。
- 未完成任何真机、四端同步、恢复、安全发布或生产部署验收。

| 里程碑 | 状态 | 证据/缺口 |
| --- | --- | --- |
| M0 | 基线审计已建立；发布许可仍需复核 | UPSTREAM_AUDIT / LICENSE_AUDIT / ARCHITECTURE / lock；存在词库历史来源残余风险 |
| M1 | 进行中 | Rust 4 项测试、Android 原生库已编译；APK/模拟器待验；三端宿主未迁移 |
| M2 | 未完成 | 统一个人数据尚未实现 |
| M3–M6 | 未开始 | 无同步、E2EE、恢复、四端验收或正式包 |

## 已运行验证

- `cargo test -p zapime-core`：4 passed，0 failed（实际输入/选词、stale generation、退格/取消、非法输入边界）。
- `cargo build --locked -p zapime-core --release --target aarch64-linux-android`：通过（NDK 28.2.13676358）。
- `scripts/fetch_resources.py`：锁内拼音、英文、SCOWL 声明和 manifest 均通过大小/SHA-256；`includes_unlicensed_inputs=false`。
- Android APK 首次打包：原生构建通过，因未安装 javac 中止，尚无可安装包。

## 限制与继续任务

1. 补齐 JDK、编出 APK，并尝试软件模拟器；无 /dev/kvm，不冒称真机。
2. 核验原生输入、焦点与密码框；修复结果后提交。
3. 建立 CI 与四端工具链检查，逐步迁移 iOS/macOS/Windows 原生身份与服务。
4. 继续统一 SQLite 数据、版本化导出/恢复与候选规则。之后独立同步服务、端到端加密与配对。
5. macOS/Xcode、Windows/MSVC/真实系统、签名凭据与三星真机均不可用；域名未核实购买。不创建定时任务。
