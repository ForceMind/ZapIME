# ZapIME 项目执行状态

更新时间：2026-10-22 02:40:58 +0800；第 10 次记录汇报；距第 9 次约 16.7 分钟。

## 当前事实

- 用户明确要求保持 Public，早期 Private 限制由本次指令覆盖；不再等待浏览器登录。
- main 基线 7b72755e426e90ca52bdac4786c3b56ea94f6008；PR #2/#4 已合并，合并后的 CI 39384110640 全绿。
- 本切片分支 codex/m1-apple-native-hosts，Refs #1；新 SHA 和 PR 以分支/PR 的实际记录为准。
- 已加入有界 C ABI、macOS IMK 宿主、iOS 原生 App 与键盘扩展源码和 Apple CI；macOS 原生编译和 ad-hoc 签名校验已在 CI 39651249265 通过；iOS 首轮因 Xcode 同时请求 x86_64/arm64 而 Rust 仅有 arm64 归档导致链接失败，已改为显式单架构匹配，待重跑；不能称系统输入通过。

## 已有实现与验证

- Android 独立 InputMethodService，离线拼音、英文、候选选择与长按置顶。arm64/x86_64 开发 APK 已构建；API 28 x86_64 模拟器基础输入和管理页短语到其他 App 上屏闭环已有 PASS 证据，未声称三星/ARM 真机验证。
- Rust SQLite 五类个人数据、修订冲突、幂等写入、tombstone、原子归档与空库恢复；Android 原生管理及明文备份 UI。
- XChaCha20-Poly1305 认证密文、Argon2id 加密恢复包与恢复码已有模块实现。销毁原密钥后仅凭恢复材料恢复新库并解密原数据的测试通过；不是云端或四端恢复验收。
- 本切片本地 cargo test --workspace --locked：23 项集成测试通过（原 20 + ABI 3）。新测试覆盖真实外部字节缓冲、无效/过大输入、会话关闭和跨线程句柄隔离。fmt 和严格 Clippy 均通过。
- 固定 MSIME 97d652a49cabb0887355b84186af2aa9032d3814 来源校验通过；未改子模块，未复制 msime-cloud 实现。
- 本地初次 ABI 测试曾失败：合成资源与资料路径嵌套，被上游路径隔离拒绝；已拆分资源目录，未削弱校验，重跑通过。

## 平台与阶段

| 部分 | 状态 | 尚缺 |
| --- | --- | --- |
| M0 | 来源与许可基线建立 | 正式发行再核依赖与词库许可、对应源码 |
| Android / M1-M2 | 原生模拟器闭环通过 | ARM 真机、性能、系统密钥库与加密恢复 UI |
| macOS / M1 | IMK 宿主源码及构建脚本新增 | 原生 CI、输入源安装、真实客户端、签名公证 |
| iOS / M1 | 原生 App/键盘扩展源码新增 | Xcode/模拟器桥接测试、系统键盘启用、设备签名 |
| Windows / M1 | 独立 TSF DLL 原生 CI 通过 | 系统安装与真实应用输入 |
| M3 | 独立服务源码和测试新增 | PostgreSQL/容器 CI 收敛、四端客户端与核对码配对 |
| M4-M6 | 未完成 | 四端互通、新服务器恢复、成熟 UI、真机与正式发布 |

## 边界和下一步

- Apple 本切片默认不学习、不接入个人数据和网络；iOS 不要求完全访问，不包含自动剪贴板监听。共享线程与宿主构建证据不能替代系统键盘输入验收。
- SQLite 与开放 JSON 是明文，逻辑删除不是安全擦除；当前 Android 归档入口有明文提示。OS 密钥托管和加密恢复 UI 尚未接入。
- 现有 arm64 开发 APK SHA-256：58b42cff392e684d50a0034bd1adfc41cc5cf6bf0542308d73a017106a4886f7；来源功能提交 b8b733371c6351a8d0c24928ad1772aeceba5805。
- 工作目录被平台维护清理后，本轮已从 GitHub 重建；没有真实用户数据。Linux 可运行 Rust；Apple/Windows 原生构建通过 CI 执行。没有 Apple 签名身份、三星真机或部署服务器权限。
- 继续修复 Apple 原生 CI，增加键盘生命周期和文本代理测试，再迁移 Windows TSF。随后独立实现云服务及四端数据桥接；不以定时任务代替研发。

## CI 收敛记录

- 39651249265：Windows/macOS/Linux 共享核心全绿，macOS IMK 原生包编译/签名校验通过。iOS 失败在模拟器双架构链接，已限制到实际 Rust 构建架构；未绕开桥接测试。
- 同轮 Android 两个 APK 均构建通过，但 emulator-runner 下载模拟器时报告 `Error on ZipFile unknown archive`，测试尚未启动，不计设备通过。继续重跑核实下载问题，保留设备测试门禁。

## Windows 原生切片

- 分支 codex/m1-windows-tsf（暂基于 Apple PR #5，合入前与 main 对齐），Refs #1。
- 新增真实 TSF DLL 源码、独立 CLSID/Profile、当前用户注册/回滚、同步编辑锁、候选浮窗、生命周期清理及 Rust C ABI 输入路径。默认不学习、不联网。
- 新增 Windows SDK 构建脚本与实际 DLL 装载/COM 生命周期/打包词库桥接测试，CI 待运行；Linux 不具备 Windows SDK，不声称本地原生编译通过。
- 尚未系统注册或验证记事本/浏览器输入，焦点和异常编辑锁仍需真实客户端测试；保留 M1 未完成。构建/边界测试与系统输入验收分别报告。

## 独立服务切片

- 分支 codex/m3-ciphertext-sync-service，暂基于 Windows PR #6；Refs #3。
- 独立 Go/PostgreSQL 服务实现账号 UUID、管理员 CLI 初始化/恢复访问凭据、设备授权/撤销、令牌摘要和到期、密文存储、事务游标、幂等、修订冲突、持久 tombstone 与账号配额。没有复制 msime-cloud。
- API 默认只绑定回环；Compose、可选 HTTPS 反代、无 root 镜像、运行时依赖许可收集、数据库备份和空库恢复脚本已写入。尚未在用户服务器部署。
- 新增真实 PostgreSQL 事务/HTTP/隔离/撤销/配额测试，CI 强制数据库存在；本地没有 Docker/可用非 root PostgreSQL 进程权限，所以这些测试明确 SKIP，不计本地通过。纯 Go 协议/边界测试和 race/vet 通过。
- Rust 合成密文/恢复 fixture 已编译，加入跨进程恢复与伪造删除标志拒绝验证；完整容器灾备演练等待 CI，不提前声明通过。
- 设备授权 API 只分配服务凭据，不传递 E2EE 根密钥；没有密码登录、邮件、核对码配对或 OS 密钥库。客户端需要独立映射本地修订和服务器游标；四端同步未接入。
- Go 1.27.3 官方下载归档已核验 SHA-256；运行时 7 个模块（含自身）许可文本收集通过。容器构建会复核并携带清单，依赖由 go.sum 锁定。

## 最新原生 CI 事实

- Apple PR #5 head 7a272ce0c8abd3ecb188edf33c2ac15402dbeee5：Windows/macOS/Linux 共享核心、Android 原生设备测试通过；macOS IMK、iOS App 和键盘扩展均编译通过，iOS 模拟器桥接测试仍在执行（39652099898）。
- Windows PR #6 head 1f884661622041f4c70c112781a28c20c8025f55：原生 TSF DLL、COM 生命周期和打包词库桥接测试通过（39653361783）。首轮链接曾误列 msctf.lib，按上游的 COM/uuid 方式修正并重跑通过。
- 这些是源码/编译/桥接证据，不是 Apple/Windows 系统激活或真实客户端输入验收，不把 M1/M3 标为完成。

## 服务端验证与依赖修复

- CI 39655004350 已通过实际 PostgreSQL 事务/HTTP 测试、Docker 构建和完整合成灾备演练：Rust 密文两设备交换、设备撤销、数据库备份、销毁原测试卷、空库还原、新进程仅凭恢复包/恢复码恢复根密钥并解密。过滤后的安全证据见 docs/evidence/sync-service-ci-39655004350.log。不是四端原生同步或生产部署。
- 随后 govulncheck v1.9.0 检出 pgx 传递依赖 x/text v0.29.0 的 GO-2026-6629 与 GO-2026-5970；已升级 x/text v0.41.0（连带 x/sync v0.22.0），本地复扫 No vulnerabilities found。新增 CI 漏洞门禁，升级后的完整 CI 待重跑。不能把这次扫描等同于完整安全审计。
- 本地协议 fuzz 3 秒执行 29440 次，未发现失败；Go race/vet 与 Rust 跨进程恢复 fixture 验证通过。
- Apple PR #5 已合并 d3ff4aed5235495c79241894bd45099403b8116f；Windows PR #6 已合并 88710d57695c51044865e313068de29165d1666f，均在对应 head 检查通过后合并。PR #7 已改为面向 main，服务端跟踪 Issue #8。
