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

- Android 键盘主题为实例级，跟随系统/本机选择，不改全局静态 Activity 配色。按键触感不得绕过系统或应用关闭；退格重复须在抬手/取消/移出/多指/隐藏/失焦/销毁时停止，异步插入仍校验焦点 epoch。九键读音复用左侧固定栏，不挤动键位。原生验收从独立 Editable 读实际正文，空编辑器的无障碍 getText 可能返回 hint；跳转已有 App 任务须明确目标页面并滚动到控件。
- Android 普通退格按 ICU 字素边界处理组合字符；不能只按一个码点删除提供的心形/肤色/旗帜/ZWJ 表情。回归需包含键盘实际插入心形和真实 Editable 字素删除，限制上下文读取长度，不在日志记录正文。
- 本机资料查询不得无 generation 更新候选映射；插入须按最新 ID/revision/期限复核。大库查询由独立只读线程执行 SQL 索引分页，只物化有界摘要，不把扫描排到输入队列。搜索使用独立不学习会话，查询词不能写入宿主正文；清空须与待处理按键保持顺序。剪贴板只在显式保存时读取纯文字，拒绝系统敏感标记；置顶不延长过期时间，永久收藏为独立副本。
- 同步策略仅是进程内调度，不得将其内存标记视作持久队列或服务器确认。所有 HTTP 尝试前必须 stage_upload；未暂存合并仅改变有界窗口的 pending 标记，保留历史快照/最后墓碑，绝不跳过冻结或冲突请求。pending=0 不等于已上传，确认看 ack_revision；平台密钥托管和宿主网络编排仍需单独接入。

- 宿主下载只用 zapime-sync::apply_encrypted_page，不能把原始服务 JSON 直接送进受信任的 apply_verified_pull。整页认证后才同事务应用记录/确认/收据/cursor；冲突整页回滚，不等于副本或解决。Record 来源 UUID 不等于服务器 device_id，外层作者/修订没有被现有 AEAD 独立签名。保留旧密钥、冻结请求和收据，禁止把旧操作改修订后重放墓碑；平台侧仍未接入。

- Android 语音模型只接受 APK 固定白名单与逐项 SHA，包内清单不可信；失败不能覆盖已有有效模型。模型/录音和按键线程分开，不添加云端回退、录音落盘/上传或假识别文本。JNI 会话只在同一语音线程 start/feed/finish/close，跨线程只 cancel；capture 有 60 秒/960000 样本与全程有界队列，隐藏/失焦/光标移动/配置变化必须取消，确认前校验原连接/epoch/selection/有界上下文。实际合成 PCM 解码、模拟器静音 AudioRecord、独立 Editable UI 验收与自然语音/ARM 真机分别记证据。第二个 voice instrumentation 同签名且只在受保护临时 CI 撤销测试包录音权限；不在个人设备执行。

- Android模型发布恢复只接受当前APK固定模型的已知暂存/备份路径，所有白名单/逐项SHA/可信清单与取消检查仍在worker；UI存在提示不代表可信安装。不要把文件系统中断状态测试称为真实断电、杀进程或SAF验收，不跳过原JNI/录音/隐私/真实服务退出门禁。
