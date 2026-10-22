# ZapIME 项目执行状态

更新时间：2026-10-22 20:23:31 +0800；第 15 次记录汇报；距第 14 次约 16.00 小时。次数按仓库状态记录累计，不将每条聊天进度重复计数。

## 仓库与提交

- 按用户 2026-10-22 明确指令保持 Public。签名凭据、设备令牌、恢复材料和真实输入不进入源码。
- 当前已合并代码基线：0e419120167f68c2e0acf3241d4180bbbe2dc7ea；文档提交不改变功能代码，实时 main 以 GitHub 为准。
- PR #5 Apple 原生宿主已合并：d3ff4aed5235495c79241894bd45099403b8116f；验证 head 7a272ce0c8abd3ecb188edf33c2ac15402dbeee5。
- PR #6 Windows TSF 已合并：88710d57695c51044865e313068de29165d1666f；验证 head 1f884661622041f4c70c112781a28c20c8025f55。
- PR #7 独立密文服务已合并：55efec2610b5a38bf8638ccb1a7f85d44050e8bf；验证 head 47822be7bc0c6554b8ceff002fb71a42c7950c05。
- PR #11 持久上传准备层已合并：eb8d2ed6f941fe3a337d59ac8b2dc80339a5c3b1；验证 head ec1d39219c5980fd8965da7760bec7824c06fffc。
- PR #9 原生焦点/通知修复已合并：936dd889f47d0a3db41d358a77fd675a37711c3b；验证 head 8d43f6f398981324cb5fbb41fbec8852826519bb，全部相关检查通过，包括 3 项实际执行的 iOS 模拟器测试。
- PR #12 macOS 系统输入实验：head 616ef358b95edf2d1dc9e7a1eeba94fc3ae9bfbf，仍为草稿。三轮均编译通过，但输入源选择返回 -50；第三轮 39665778845 已确认父/子启用、IMK 启动及最新 enabled list，30 次仍失败，未进入输入断言，原因未解决。
- PR #13 部署目标修正已合并：c6aea091e15d752ae52b6505d0adeb489f95359a；head 8fb16a837e46a0cb7eef664df1d084b9182c7a5f 的 ARM IMK 构建步骤、三系统 Rust、Windows 和服务已通过，同脚本 Intel 构建也通过。仅按改动相关门禁合并；通用工作流的 Android 回归失败，Apple 作业随后完整完成并通过（含 3 项模拟器测试），仍不宣称整条工作流全绿。
- PR #14 Android 候选验收定位及独立门禁已合并：5fe02fd0ee772a00eede870293d42ef8793e1fb5；head 48ef95e43d045fa4a6288ce5e6c6fc272bf9e4ea。专用 CI 39669035178 两轮完整输入通过；39669035212 三系统 Rust、39669035039 Windows 和 39669035363 服务通过，额外 Apple 原生作业仍在运行，不宣称整条通用工作流全绿。未修改产品输入逻辑，不把测试定位修正当成物理触摸稳定性修复。
- PR #17 Android 原生视觉重设计已合并：aa35ea62301ce1a3fb1631df7e99eb39e60d1ed9；head abaf1167c8ad195dd8f8d0b5b88ad70f01105fd1。四排键位、数字/符号面板、候选/工具区、设置与个人资料卡片；39775439102 两轮输入断言通过，但人工日志复核发现服务退出崩溃，不将其记作无崩溃通过。
- PR #18 生命周期修复已合并：0e419120167f68c2e0acf3241d4180bbbe2dc7ea；head fa23f867870d556d3e9c20193bed0f6cb3d509a1。父类输入回调完成后才关闭 worker。39776837197 双架构 APK、API28 两轮输入及切回 AOSP 后真实服务退出均通过；两轮 runtime 日志无 app fatal。三系统 Rust、Windows 39776837151、服务 39776837048 通过；额外 Apple 原生作业仍在运行，不宣称完整通用工作流全绿。
- 跟踪：#1 原生基线、#3 本地个人数据、#8 独立服务及四端同步。所有后续 SHA/合并结果以 GitHub 为准。

## 实际验证

| 范围 | 已有真实证据 | 尚不能宣称 |
| --- | --- | --- |
| Android | arm64/x86_64 APK；API28 原生输入、隐私字段、管理页新增短语到其他 App 上屏；39665833406 曾 focus_return 失败；PR #14 两轮输入通过；最新 PR #17/#18 重做原生 UI，并以 39776837197 两轮输入/工具/数字/符号/大小写/资料及真实退出、无 app fatal 复验通过 | 候选物理触摸布局稳定性、ARM/三星真机与正式发布 |
| macOS | 独立 IMK App 的 ARM/Intel 编译与 ad-hoc 签名；三轮 TIS 实验均在选择阶段 -50，未上屏 | 已完成系统启用/真实 App 输入、通用包或公证 |
| iOS | 原生 App + 真正键盘扩展编译；基线模拟器桥接与专用 Thread 测试，39652099898；PR #9 的 UIKit 通知重入回归及其余 2 项模拟器测试，39661698551 通过，39665833406 的 Apple 作业再次通过 3 项测试 | 系统键盘激活、其他 App 输入、真机签名与发布 |
| Windows | TSF DLL 编译、实际装载、COM 生命周期/锁/参数/身份隔离、打包词库 nihao→你好；39653361783、39661698399 及最新 39669035039 通过 | 已注册并在记事本/浏览器输入、安装器或签名 |
| Rust | 30 项集成测试、fmt、严格 Clippy；Linux/macOS/Windows，39661235469；本地同样通过；最新 39669035212 三系统再次通过 | 四端产品全部完成 |
| 服务端 | 实际 PostgreSQL 16 事务/HTTP/race；Docker；两设备实际 Rust 密文与销毁原库后的空库恢复，39657713075；SQLite v2 回归 39661235583，最新 39669035363 同样通过 | 四端联网客户端、生产部署或完整安全审计 |

## 当前实现

- Android 已重设计真正的原生键盘、候选、工具、数字/符号面板以及设置/个人资料卡片；QQ 仅为布局参考，图标为自绘，无第三方皮肤。原始截图及边界见 [Android UI](design/ANDROID_UI.md)。其余三端 UI、暗色/大字号/横屏专项适配和九键仍未完成。
- 固定 MSIME 97d652a49cabb0887355b84186af2aa9032d3814 引擎、来源锁与许可；未改子模块、未复制 msime-cloud。词库资源校验 SHA-256；不捆绑神经权重/语音服务。
- Rust SQLite 提供词库、候选规则、短语、收藏、剪贴板；事务修订/幂等、墓碑、开放归档和空资料库恢复；Android 已有管理、置顶与明文导出/恢复 UI。
- SQLite v2 新增显式持久待发序列、不可变操作快照、单账号绑定、首次密文请求原样重试及服务器修订映射。上传确认不覆盖后续编辑、不推进拉取 cursor；重启/VACUUM 后顺序稳定。尚未调用网络或应用远端记录。开放归档仍为 v1；旧版降级需导出后恢复到全新库，详见 SYNC_STATE.md。
- Apple/Windows 最小宿主采用独立身份和目录，默认不学习、不联网，不接入个人资料与云端；不是上游全部成熟功能的迁移结果。
- XChaCha20-Poly1305 认证密文及 Argon2id 恢复包/恢复码已实现；根密钥与归档同时封装，服务器不接收根密钥/恢复码。
- 独立 Go/PostgreSQL 提供管理员 CLI 账号/首设备/访问凭据恢复、owner 授权/撤销、摘要令牌到期、事务内授权复核、密文日志、单调游标、条件更新/冲突、幂等、永久墓碑和 256 MiB 保守账号配额。
- Compose、可选 Caddy HTTPS、非 root 镜像、运行时 Go 许可收集、数据库备份及拒绝非空库覆盖的恢复脚本已写入。未在用户服务器部署。

## 测试与失败记录

- 服务 39657713075 全绿，明确启用 bash pipefail，避免 tee 隐藏测试退出码。实际验证幂等/重放拒绝、并发旧修订冲突、墓碑、分页、账号隔离、越权/撤销/凭据轮换后的旧 actor 拒绝、令牌摘要和配额原子拒绝。
- 合成灾备：两设备交换实际 Rust AEAD 密文；撤销设备；pg_dump；销毁原测试数据库卷；空库 pg_restore；新进程仅用私密恢复材料恢复根密钥/归档并解密。撤销状态仍有效，非空库重复恢复拒绝。保存证据 docs/evidence/sync-service-ci-39655823283.log；最新服务 CI 链接为 https://github.com/ForceMind/ZapIME/actions/runs/39669035363。
- govulncheck v1.9.0 初次检出 x/text v0.29.0 的 GO-2026-6629 / GO-2026-5970。升级到 x/text v0.41.0（x/sync v0.22.0），本地及升级后的 CI 均 No vulnerabilities found；不能替代完整审计。本地协议 fuzz 3 秒共 29440 执行，无失败。
- 上传状态 7 项新增测试覆盖首次密文保留/重启重试、确认幂等/错误拒绝、旧确认不覆盖新编辑、跨账号/乱序拒绝、独立快照、v1 迁移与未来版本拒绝。其形状占位密文不是 AEAD 成功证据，实际密码交换由独立灾备演练验证。
- PR #9 Android 首轮暴露同名编辑文本/按钮定位歧义，已限定 Android Button 控件并增加阶段诊断，最新实际输入回归通过。iOS 首轮新测试编译通过，但旧 xcresult 路径导致测试未执行；已按 run/attempt 唯一路径保存，最新 39661698551 实际执行 3 项测试并通过；证据 docs/evidence/ios-native-ci-39661698551.log。
- 原生初轮的 iOS 架构链接、Windows msctf.lib 错误均已修正并通过后续构建。固定词库下载曾遇 HTTP502，只重跑失败 Windows job 后通过，不作为代码失败或输入成功的替代证据。
- PR #12 在独立临时 runner 中使用 TIS 注册/选择 ZapIME，准备向自己的 Cocoa 文本框派发合成事件；若 GUI、注册或选择失败则明确非零退出。三轮均在选择阶段失败，证据 docs/evidence/macos-input-ci-39663612323.log、macos-input-ci-39664522870.log、macos-input-ci-39665778845.log；不计为系统输入验收，更不等于真实用户物理按键测试。

- 新回归 39665833406：Android focus_return 预期 你好qw你好（6），截图实际 你好qw你好啊（7）；密码框另有两次掩码输入。疑似候选列表刷新后旧坐标命中另一候选，尚未证明根因。PR #14 对指定 Button 走 ACTION_CLICK，读取独立测试 Activity 的真实 Editable/组字标记，允许过期节点重查但拒绝任何意外正文；键盘和焦点仍用触屏注入。两轮验收初轮 39667413535 第一轮通过、第二轮输入前键盘未显示（dumpsys: input started、show requested=false）；夹具显式切系统 IME 后清空，并由测试 Activity 请求 SHOW_IMPLICIT，专用 39669035178 两轮均通过；不直接调用核心或写入正文替代输入。失败证据 docs/evidence/android-input-ci-39665833406.log。
- 新增独立 Android 门禁 .github/workflows/android.yml，避免被旧 Apple 模拟器作业占据通用并发组。两轮各自保留证据；失败夹具日志 docs/evidence/android-repeat-ci-39667413535.log，成功日志 docs/evidence/android-two-round-ci-39669035178.log。候选使用指定 Button 的 ACTION_CLICK，键盘/焦点使用触屏；不会将此结果当成候选物理触摸布局、ARM 真机或全平台验收。Apple 最新已完成的 3 项模拟器证据 docs/evidence/apple-native-ci-39665833406.log。

## 本次 UI 与运行时复验

- 39774429539 首轮工具页已打开，随后无障碍节点定位失败；增加动态面板事件、节点刷新与失败层级诊断。失败日志 android-ui-ci-39774429539.log 保留。
- 39775439102 两轮输入断言 PASS，但第二轮 runtime-errors.log 含退出崩溃；根因为既有 onDestroy 先关 worker，父类再进入 onFinishInput 提交任务。保留 android-teardown-crash-39775439102.log，不把输入断言当作运行时无崩溃证明。
- 39776837197 实际切换系统输入法、等待 ZapIME 服务退出并检查当轮 fatal；两轮均通过。下载 artifact 并校验 SHA-256，两轮均有七张 320×640 原始截图；runtime 日志均只有日志头，服务退出记录不含 ZapInputMethodService。证据见 android-ui-ci-39776837197.log、同号分轮 runtime/teardown 日志与 ui-android-39776837197/。普通键位/焦点是触屏；候选仍用指定 Button 的 ACTION_CLICK，物理选词布局与 ARM 真机仍需验证。
- 本地仅运行 Python 语法检查与差异检查；没有本地 Android SDK，没有把 CI 构建写成本机构建。后续文档/截图归档不改变产品 APK；单次本机脚本未执行的服务退出检查明确输出 SKIP，CI 门禁行为保持一致。

## 阶段与限制

M0 来源与许可基线已建立，正式发行仍需复核。M1 四端都有源码和原生构建证据，只有 Android 已有原生输入闭环；Apple/Windows 系统激活和真实 App 验收未完成。M2 主要完成共享数据及 Android 管理，其余三端仍缺管理/个人规则接入。M3 独立服务及合成恢复已验证，客户端持久上传准备层已实现；四端联网客户端、图形账号、核对码配对、OS 密钥托管及密钥轮换未完成。M4–M6 未完成。

本地只有 Linux，没有 Apple 签名身份、三星/ARM 真机或交互式 Windows 桌面。Docker/真实 PostgreSQL 通过 CI 验证，本地对应测试明确 SKIP。SQLite 中 records/operations 的业务正文与开放 JSON 仍为明文，sync_uploads 仅保存密文上传请求；逻辑删除不等于安全擦除。加密恢复 UI 尚未接入，没有域名购买、生产部署或正式签名发布。

## 接续任务

1. 实现独立后台 HTTP/TLS 同步与远端认证应用、冲突副本、cursor 原子推进；现有持久上传准备层仍未联网。
2. macOS TIS -50 仍未解决，在可正常激活输入源的登录会话继续定位；Windows/iOS 系统激活、第三方 App 和候选物理触摸仍需实际验收，不猜测签名或授权原因。
3. 接 Android Keystore、Apple Keychain、Windows 受保护存储和加密恢复 UI；核对码配对、根密钥传输/轮换需单独验收。
4. 延续 Android 新视觉到另外三端的真实宿主；补充暗色、大字号、横屏、候选物理触摸与三星真机验收，不能只替换截图。
5. 完成四端个人管理与同步、Apple/Windows 真实应用输入和安全字段、全设备丢失与空服务器重建演练，再做生产部署与签名发布。

未完成项不会因文档、一次构建或一次合成测试而标作整体完成。不创建定时任务替代研发。
