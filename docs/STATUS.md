# ZapIME 项目执行状态

更新时间：2026-10-20 14:56:46 +0800；本次 Work 工程记录 #5；距记录 #4 约 22.3 分钟。

## 当前事实

- 仓库保持 Private；初始 main = 076961750bab1657b8a98c5e019daa234861a54a。
- M0/M1 跟踪 #1，原生基线 Draft PR #2；M2/恢复跟踪 #3，数据切片 Draft PR #4。
- 上游 MSIME 固定 97d652a49cabb0887355b84186af2aa9032d3814，完整源码 submodule；未复制 msime-cloud 实现。
- 已实际构建 Android arm64-v8a / x86_64 APK，签名、APK 16 KB zip 对齐、包名、IME 服务与无 INTERNET 权限检查通过。
- API 28 本地软件模拟器已通过基础原生输入：离线 nihao→你好、实际上屏、英文、退格、密码框不学习、焦点切换；不是三星真机验收。
- Android 已实现候选长按置顶、个人词库/候选规则/短语/收藏/显式本机剪贴板的原生管理页、明文备份导出和空资料库恢复入口。管理页新增快捷短语 wx 后，返回真实输入框通过键盘上屏的 API 28 本地闭环已通过；四端产品仍未完成。
- 共享 SQLite 数据、XChaCha20-Poly1305 密文及 Argon2id 恢复包模块已实现；尚未连接 OS 密钥库、账号、设备配对或云服务器。

## 代码与验证

- 输入基线最新来源以 PR #2 head 为准；已保存 5f2eef2149f4251986c70daa8bf2f050116dca3a。基础输入本地 instrumentation 的 PASS 记录已获取。
- 数据切片功能提交 b8b733371c6351a8d0c24928ad1772aeceba5805 已通过本地干净构建的 20 项测试和严格 clippy；后续仅更新设备测试驱动与本报告，当前 head 见 PR #4。
- `cargo test --workspace --locked`：20 项集成测试通过（core9 / data8 / crypto3）。
- `cargo fmt --all --check` 与 `cargo clippy --workspace --all-targets --locked -- -D warnings`：通过。
- 个人规则/快捷短语真实进入共享候选和上屏；重开恢复、旧候选拒绝、两连接修订冲突、幂等重试、归档损坏/非空覆盖拒绝均有测试。
- 模块级灾备：销毁原根密钥和旧库，仅用恢复码与加密包在新 SQLite 还原收藏，并解开原密文；通过。不是四端/空服务器恢复验收。
- 生产词库探针 nihao/zhongguo/shurufa 通过；资源大小与 SHA-256 校验、manifest 未授权输入排除检查通过。
- GitHub CI 的 Windows/macOS/Linux 共享核心及 iOS simulator 目标 check 已有成功记录（39378255837）。当前两个 PR 的最新设备 CI 仍在收敛，不因历史成功就合并；20 项模块测试、fmt/clippy 与跨系统共享核心检查已通过。
- CI 39379910157：双 ABI 构建通过，设备输入失败在软键盘未弹出；改用真实触摸并指定 SOURCE_TOUCHSCREEN。之前另有 AOSP 默认键盘 ID 失效，已修为专用设备显式启用 ZapIME。失败记录保留，未声称全绿。
- Android 包内新增逐包依赖许可清单；核心非 dev 图 111 条目，明确许可及缺文件版本映射；正式发行许可与源码提供仍需最终审核。

## 里程碑

| 里程碑 | 状态 | 缺口 |
| --- | --- | --- |
| M0 | 基线建立 | 发布时复核传递依赖、词库历史来源与对应源码 |
| M1 | Android 基础输入本地通过 | 最新 CI 收敛；三端原生宿主迁移、设备与签名 |
| M2 | 共享数据与 Android 管理实现中 | Android 管理闭环本地通过；其余三端；进一步编码标准化与加密恢复 UI |
| M3 | 密文/恢复模块实现，服务未开始 | Go/PostgreSQL、账号、配对、轮换、异步同步 |
| M4 | 未完成 | 四端真实互通、冲突与撤销 |
| M5 | 模块恢复已有证据 | 全新服务器/四端恢复、加密恢复 UI、成熟 UI/UX |
| M6 | 开发 APK 与 CI 已建立 | 真机、安全审计、性能、安装签名/公证与正式发布 |

## 继续顺序与限制

1. 收敛最新 CI 和 Android 管理页到键盘的实际闭环；验证通过后按 PR 规则合并，不强推。
2. 迁移 iOS/macOS/Windows 的独立身份、路径和原生设置。共享核心构建通过不能替代原生输入法验收。
3. 独立实现 Go/PostgreSQL 服务、设备配对/撤销、端到端同步，再连接四端。
4. 完成平台密钥库、恢复码界面、空服务器迁移与全设备丢失恢复演练。
5. 本地只有 Linux 与 Android 软件模拟器，无 /dev/kvm、三星真机、Apple 签名身份或原生 Windows 桌面会话。GitHub 的 macOS/Windows runner 可用于后续原生编译，但当前未完成其宿主迁移。
6. SQLite 和开放 JSON 归档为明文，逻辑删除不代表安全擦除；加密恢复包尚无用户 UI。未购买域名、未部署服务器、未宣称完整四端产品；不创建 ChatGPT 定时任务。

## 本次交付与证据

- Android arm64 开发包：ZapIME-0.1.0-dev-arm64-v8a.apk，约38 MiB；仅开发签名，没有三星真机验收。
- 交付包 SHA-256：58b42cff392e684d50a0034bd1adfc41cc5cf6bf0542308d73a017106a4886f7；源码功能提交 b8b733371c6351a8d0c24928ad1772aeceba5805。
- 本地 API 28 x86_64 原生输入/管理闭环 PASS 日志和截图：docs/evidence/android-api28-instrumentation.log、android-api28-shortcut.png。所有文本均为合成数据。
- 设备测试使用独立测试 App，避免重启输入法进程；启用前查询全部已安装输入法（ime list -a），不误查仅已启用列表；真实触摸点击候选以避免过期无障碍节点。
- 对应签名、对齐、manifest、依赖通知和词库校验在 scripts/build_android.sh 中执行。
