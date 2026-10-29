# ZapIME 工程约定

- 首先阅读 docs/DEVELOPMENT_PLAN.md、docs/STATUS.md、docs/DECISIONS.md。仓库按用户 2026-10-22 明确指令保持 Public；不要擅自修改可见性。
- vendor/msime 固定提交，禁止未审计更新；上游版权、许可证和 NOTICE 原样保留。修改该子模块前阅读其中 AGENTS.md。不要复制 msime-cloud 实现。
- 输入线程不等网络。个人数据默认本地；云上传需显式开启。密码及 NO_PERSONALIZED_LEARNING 字段不学习。测试数据必须合成。
- 工程默认 Rust 1.97.1；从根运行 cargo test --workspace、cargo fmt --all --check、cargo clippy --workspace --all-targets -- -D warnings。
- 同步准备层保留不可变操作快照和首次密文请求；本地 revision、服务器 revision、拉取 cursor 分开。上传确认不得覆盖后续编辑或推进拉取 cursor；认证远端应用已有共享模块，永久冲突副本/解决和宿主联网尚未实现。
- Go 服务使用 go.mod 固定工具链。在 services/sync 运行 go test -race -count=1 -v ./...；事务验收必须配置真实 PostgreSQL 的 ZAPIME_TEST_DATABASE_URL 和 ZAPIME_REQUIRE_DATABASE=1。显式 SKIP 不等于数据库测试通过；容器灾备入口为 scripts/sync_service_smoke.py。
- Android 原生构建：ANDROID_SDK_ROOT 指向 API 36 / build-tools 35 / NDK 28.2.13676358；bash scripts/build_android.sh arm64-v8a。APK 构建通过不等于系统输入验证。
- Android 独立 CI 为 .github/workflows/android.yml。双轮脚本只运行于临时 GitHub 模拟器，会清空两个合成测试包；本地使用 android_smoke.py 的专用设备入口。候选无障碍动作验收不能替代物理触摸布局/真机验收。
- Android 每轮还需实际切回 AOSP IME、确认 ZapIME 服务退出并拒绝其运行时 fatal；只有输入 PASS 不足以通过。旧日志清空仅限受保护的临时 CI 模拟器，不能扩展到用户日常设备。父类 onDestroy 会调用 onFinishInput，必须在回调完成后才关闭 worker。
- API28保留AOSP门禁；API35/36的Google镜像若没有AOSP，只允许选择pm list packages -s核实的其它预装系统IME，记录精确component/API/system证明，仍必须实际切换、等待ZapIME服务退出并拒绝fatal，不能任意选择第三方或跳过退出。现代测试素材从testOnly验收APK的assets读，不让ADB伪造App私有/Android-data目录所有权，不给产品加存储权限或debuggable。模型/PCM仅进入测试APK，产品APK继续单独模型导入；测试包须保留GPL及模型许可。
- Git submodule update --init 后 python3 scripts/verify_upstream.py 核验来源。不要提交 target、凭据、私钥或真实用户文本。
- 每个独立切片用分支和 PR，禁止强推。审查当前差异、跑对应检查，按仓库规则合并。
- docs/STATUS.md 区分源码、编译、模拟器、真机及签名证据；更新时记录时间与可追溯提交。不得用定时任务代替开发。
- R2/S3 新写入必须在事务内授权/重放/条件修订/配额校验后、数据库提交前完成；对象条件创建、读时校验 SHA，缺失不得变成空页。旧数据库内联兼容；schema v2 不允许旧二进制降级。
- 服务完整备份包含 .dump 和 .dump.objects.tar；先数据库快照再导出追加对象超集。恢复到空库，导入后验证引用；禁止自动删除对象历史或把管理访问凭据当作 E2EE 恢复密钥。
- deploy/install.sh 及破坏性安装/恢复演练只在自有 Linux 服务器或隔离 CI 执行。scripts/server_install_smoke.py 必须保留 GITHUB_ACTIONS、root、空安装目录保护，不在用户 Mac 或已有生产实例测试清库。

- 在线密文缓存仅在数据库授权/撤销检查之后使用，不能代替实际对象写入确认；备份、恢复与 verify 命令绕过缓存读取持久后端。缓存专项的合成内存对象测试必须清理自己账号的 DB 引用，避免污染真实 S3 全库归档验收。客户端合并不能重写已冻结/已发送操作快照；HTTP 合批不等于 R2 对象合批。

- 九键沿用固定引擎，读音选择与候选均校验代次；数字面板不得误送九键编码，显式读音约束也应用于个人词。语音 CPU 探针不是 APK 录音/准确率验收；只用合成音，下载与推理分离，模型校验、许可和网络隔离不得跳过。iOS 键盘不能直接访问麦克风，不默认云端回退或上传录音。

- 语音整句评测固定合成定义先于推理；参考/输出/全部错误保留，不按结果改难句。CER口径须版本化、分母加权，重复轮次不算新增样本，不能用100%-CER或片段PASS冒充真人准确率。默认报告不写正文；只有固定非私密合成集显式include-text可归档，真实录音/手机门禁另行明确范围。

- Android 键盘主题为实例级，跟随系统/本机选择，不改全局静态 Activity 配色。按键触感不得绕过系统或应用关闭；退格重复须在抬手/取消/移出/多指/隐藏/失焦/销毁时停止，异步插入仍校验焦点 epoch。九键读音复用左侧固定栏，不挤动键位。原生验收从独立 Editable 读实际正文，空编辑器的无障碍 getText 可能返回 hint；跳转已有 App 任务须明确目标页面并滚动到控件。
- Android 普通退格按 ICU 字素边界处理组合字符；不能只按一个码点删除提供的心形/肤色/旗帜/ZWJ 表情。回归需包含键盘实际插入心形和真实 Editable 字素删除，限制上下文读取长度，不在日志记录正文。
- 本机资料查询不得无 generation 更新候选映射；插入须按最新 ID/revision/期限复核。大库查询由独立只读线程执行 SQL 索引分页，只物化有界摘要，不把扫描排到输入队列。搜索使用独立不学习会话，查询词不能写入宿主正文；清空须与待处理按键保持顺序。剪贴板只在显式保存时读取纯文字，拒绝系统敏感标记；置顶不延长过期时间，永久收藏为独立副本。
- 同步策略仅是进程内调度，不得将其内存标记视作持久队列或服务器确认。所有 HTTP 尝试前必须 stage_upload；未暂存合并仅改变有界窗口的 pending 标记，保留历史快照/最后墓碑，绝不跳过冻结或冲突请求。pending=0 不等于已上传，确认看 ack_revision；平台密钥托管和宿主网络编排仍需单独接入。

- 宿主下载只用 zapime-sync::apply_encrypted_page（或其preserving_failure包装），不能把原始服务 JSON 直接送进受信任的 apply_verified_pull。整页认证后才同事务应用记录/确认/收据/cursor；冲突整页回滚，不等于副本或解决。Record 来源 UUID 不等于服务器 device_id，外层作者/修订没有被现有 AEAD 独立签名。保留旧密钥、冻结请求和收据，禁止把旧操作改修订后重放墓碑；平台侧仍未接入。

- Android 语音模型只接受 APK 固定白名单与逐项 SHA，包内清单不可信；失败不能覆盖已有有效模型。模型/录音和按键线程分开，不添加云端回退、录音落盘/上传或假识别文本。JNI 会话只在同一语音线程 start/feed/finish/close，跨线程只 cancel；capture 有 60 秒/960000 样本与全程有界队列，隐藏/失焦/光标移动/配置变化必须取消，确认前校验原连接/epoch/selection/有界上下文。实际合成 PCM 解码、模拟器静音 AudioRecord、独立 Editable UI 验收与自然语音/ARM 真机分别记证据。第二个 voice instrumentation 同签名且只在受保护临时 CI 撤销测试包录音权限；不在个人设备执行。

- JNI仅以成功接受的有效count前缀累积“是否出现非零PCM”，无样本/全零必须空部分与最终文本并略过尾部解码；±1非零不设阈值，标记不跨会话。此规则不是VAD/权限/系统静音推断，不能替代配置回调、取消或语音准确率验收。C++策略合成单测、真实JNI及空预览禁止插入分别保留证据。

- 语音确认在主线程固定一份非空结果并一次性消费；有界上下文仍在语音worker读取。结束时复核原连接/ticket/epoch/选区版本及绝对折叠光标、隐私/搜索/组字状态，先消费清理、最多调用一次commitText，finally再恢复界面；不能在复核后/提交前恢复UI引入重入，拒绝/异常不自动重试。真实JNI文字驱动的事务/Android Editable合成探针不等于已激活系统IME、物理确认或自然麦克风识别。

- 失败页日志只在严格密文页形状/账号/连续修订通过后保存；Saved/NotEligible/Failed必须区分，禁止把回滚当保存成功。日志永不替代AEAD、cursor或冲突解决；8页/32MiB满额拒绝新页，不自动淘汰。导入/重试仍检查账号和原cursor并重新认证；旧records归档/恢复包不含此日志，需另导出及独立保留旧密钥。不要因独立辅助表而改变既有v2记录/队列或上屏线程路径。

- 新增失败全局诊断只在GITHUB_ACTIONS+run/attempt数字+专用emulator序列全部验证后采集，每文件至多256KiB且有限超时；不得扩展到个人设备。主App或测试宿主ANR必须拒绝成功；系统Launcher启动ANR单独保留，不能删旧失败、虚构产品根因或跳过输入/退出断言。

- UI失败现场须由test-only宿主在Instrumentation.finish之前采集；finish后宿主可被系统终止，外部dumpsys不能冒充当时现场。CI全局诊断开关只由已验证脚本传数字run-attempt参数，不能默认用于普通设备。独立宿主从真实前台按钮正常打开产品设置，显示键盘先确认served editor及实际Insets可见状态；不强制shell前台、覆盖系统策略或减弱组字/真实正文断言。

- Android模型发布恢复只接受当前APK固定模型的已知暂存/备份路径，所有白名单/逐项SHA/可信清单与取消检查仍在worker；UI存在提示不代表可信安装。不要把文件系统中断状态测试称为真实断电、杀进程或SAF验收，不跳过原JNI/录音/隐私/真实服务退出门禁。

- Android同步保护材料只在专用同步worker使用SyncSecrets，主线程检查必须先于锁；不在输入worker运行Keystore/文件IO。禁止明文/SharedPreferences回退、缺key时覆盖既有密文或在日志/普通归档保存材料；返回数组由调用方清零。material域forget仍无用户流程，connection域只在用户显式移除连接时删除；测试只能清除自己的随机账号别名。E2EE保护材料尚未接账号密钥传输/同步/恢复UI，不能称四端密钥托管已完成。

- Android设备连接仅主App显式HTTPS身份检查，默认启动/键盘/语音不得触发HTTP；不跟随跳转、放宽证书/主机名、自动重试或读取服务器错误正文。连接凭据使用独立connection文件/别名/AAD；原material命名空间与格式保持，显式移除连接不得删除E2EE材料。当前未接资料库绑定、密钥传输、数据同步或配对，不能显示已同步。新增INTERNET后，临时CI以dedicated_ci核实run/attempt及emulator序列后才root设置精确产品UID的IPv4/IPv6拒绝规则；缺root/任一家族失败须失败，finally只删本轮规则，禁止flush或接管既有规则/操作个人设备。JVM回环TLS、Android本机合成探针与真人服务器/手机HTTPS分别记证据。
- 保存连接前只在同步worker经无输入会话的JNI只读核对既有SQLite sync_profile；缺少主库不创建，未知schema/损坏绑定失败，已绑定其他账号不得写vault。移除连接不清除资料库绑定。此检查只是快照，不能替代未来每次stage/apply的事务内require_profile，不添加自动绑定/换库/上传。

- 同输入框重启保留组字必须验证 restarting、原编辑器身份/选项、可预测且完整折叠在组字尾的选区以及 API30+ EditorInfo 自带初始快照；缺快照/改变文本/隐私或身份变化一律重建。不要在输入线程新增 getTextBeforeCursor 等远端读取，也不要把该修复称为所有间歇失败根因。迁移的 SelectionEchoTracker 保留固定上游 GPL 来源，成功写入记预期，不能预测的写入失效化；API28 不假称已支持此快照路径。

- 同位置组字标记丢失可能先于restartInput；只按固定上游有界100ms交接，重启取消待回报、新操作前结算。真正失去范围时提升epoch、异步reset解码器并原地finish标记，禁止在应用新正文/选区写空setComposingText；迟到结果不得上屏。Service诊断仅计数/固定原因码，不含正文、上下文或身份。
- 异步结果在标记交接期间须按序有界暂存，不能让后来结果越过或写到未验证范围；新连接验证后按序释放，真正失去范围/新epoch丢弃，超限明确重输并保留应用正文。不要把短时内存缓冲称为持久队列。

- Windows系统注册实验仍为草稿PR #40，最新实际代码c11b95d；独立进程profile门禁未通过。接续先读docs/WINDOWS_ACCEPTANCE_REPORT.md，不能省略失败、把清理PASS视为输入成功，或把临时作用域对照复制成产品安装逻辑。

- 英语伴学已有策略/有界组装与宿主提交接缝，默认关闭且无模型时不捕获，未接推理worker/UI/收藏；不能用测试英文充当译文或添加无能力开关。未来每次onStartInput都重开独立学习session，排除私密/搜索及非确认提交，单独worker且实际停止后完成回调；不扫描App全文、自动记历史、云回退或逐次翻译写R2。仅显式收藏保留所见不可变中英对，OPUS权重与运行库分开审计许可。

- 伴学核心commit必须严格核对本请求commit_source=engine；saved_data/none/缺字段/未知类型仍正常上屏但不进入学习。个人None引擎索引扩展及personal_commit标saved_data，规则只重排既有引擎行不改其来源；不缓存上次来源、不改变候选/正文/generation。直接手打fallback与核心commit分开。

- 语音功能验收的物理入口在慢IPC取消并释放App之后，须先观察公开IME Insets动画和新鲜窗口/按钮几何稳定，再重新取坐标单次触摸并核面板后置条件；inject ACK不等于action。API28隔离API30类，不用隐藏同步API/盲重试toggle；原暂停期间具名取消及1800ms门禁不放宽。工具面板保留仅同编辑器空组字非私密tools，组字仍原快照验证；原失败图/原文保留，别泛称所有#38间歇问题已修复。

- 本机加密备份独立有界worker，不在输入/同步/UI线程做KDF/IO；仅4MiB归档/6MiB密文，固定既有KDF，用途前缀区分同步恢复包，不读取/替换SyncSecrets材料。恢复只打开已存在v2库，同事务拒绝记录、绑定和操作/同步历史；不自动创建/迁移/清空。SAF写后读回不是跨提供程序原子或永久持久保证，代码另存/核对不虚构外部保存，离开不承诺回滚已经开始事务。只有dedicated_ci核实数字run/attempt/emulator后才能传zapime_ci_backup启用系统文档夹具，只删除自己新建的合成文档；正向独立空库JNI与真实SAF导出/正向导入UI/真机分别记证据，日志不含恢复码、正文或秘密页文本。

- 受保护CI的SAF夹具须在Activity临时URI授权失效前删除自己的新建文档；逐项清理并保留原始操作失败，报告必须即使清理失败也写出且要求cleanup_completed=true。不得增加产品存储权限、持久化授权或跳过提供程序来让门禁通过。
