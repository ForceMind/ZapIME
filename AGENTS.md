# ZapIME 工程约定

- 首先阅读 docs/DEVELOPMENT_PLAN.md、docs/STATUS.md、docs/DECISIONS.md。仓库按用户 2026-10-22 明确指令保持 Public；不要擅自修改可见性。
- vendor/msime 固定提交，禁止未审计更新；上游版权、许可证和 NOTICE 原样保留。修改该子模块前阅读其中 AGENTS.md。不要复制 msime-cloud 实现。
- 输入线程不等网络。个人数据默认本地；云上传需显式开启。密码及 NO_PERSONALIZED_LEARNING 字段不学习。测试数据必须合成。
- 工程默认 Rust 1.97.1；从根运行 cargo test --workspace、cargo fmt --all --check、cargo clippy --workspace --all-targets -- -D warnings。
- 同步准备层保留不可变操作快照和首次密文请求；本地 revision、服务器 revision、拉取 cursor 分开。上传确认不得覆盖后续编辑或推进拉取 cursor；远端应用和冲突解决尚未实现。
- Go 服务使用 go.mod 固定工具链。在 services/sync 运行 go test -race -count=1 -v ./...；事务验收必须配置真实 PostgreSQL 的 ZAPIME_TEST_DATABASE_URL 和 ZAPIME_REQUIRE_DATABASE=1。显式 SKIP 不等于数据库测试通过；容器灾备入口为 scripts/sync_service_smoke.py。
- Android 原生构建：ANDROID_SDK_ROOT 指向 API 36 / build-tools 35 / NDK 28.2.13676358；bash scripts/build_android.sh arm64-v8a。APK 构建通过不等于系统输入验证。
- Android 独立 CI 为 .github/workflows/android.yml。双轮脚本只运行于临时 GitHub 模拟器，会清空两个合成测试包；本地使用 android_smoke.py 的专用设备入口。候选无障碍动作验收不能替代物理触摸布局/真机验收。
- Android 每轮还需实际切回 AOSP IME、确认 ZapIME 服务退出并拒绝其运行时 fatal；只有输入 PASS 不足以通过。旧日志清空仅限受保护的临时 CI 模拟器，不能扩展到用户日常设备。父类 onDestroy 会调用 onFinishInput，必须在回调完成后才关闭 worker。
- Git submodule update --init 后 python3 scripts/verify_upstream.py 核验来源。不要提交 target、凭据、私钥或真实用户文本。
- 每个独立切片用分支和 PR，禁止强推。审查当前差异、跑对应检查，按仓库规则合并。
- docs/STATUS.md 区分源码、编译、模拟器、真机及签名证据；更新时记录时间与可追溯提交。不得用定时任务代替开发。
- R2/S3 新写入必须在事务内授权/重放/条件修订/配额校验后、数据库提交前完成；对象条件创建、读时校验 SHA，缺失不得变成空页。旧数据库内联兼容；schema v2 不允许旧二进制降级。
- 服务完整备份包含 .dump 和 .dump.objects.tar；先数据库快照再导出追加对象超集。恢复到空库，导入后验证引用；禁止自动删除对象历史或把管理访问凭据当作 E2EE 恢复密钥。
- deploy/install.sh 及破坏性安装/恢复演练只在自有 Linux 服务器或隔离 CI 执行。scripts/server_install_smoke.py 必须保留 GITHUB_ACTIONS、root、空安装目录保护，不在用户 Mac 或已有生产实例测试清库。

- 在线密文缓存仅在数据库授权/撤销检查之后使用，不能代替实际对象写入确认；备份、恢复与 verify 命令绕过缓存读取持久后端。缓存专项的合成内存对象测试必须清理自己账号的 DB 引用，避免污染真实 S3 全库归档验收。客户端合并不能重写已冻结/已发送操作快照；HTTP 合批不等于 R2 对象合批。

- 九键沿用固定引擎，读音选择与候选均校验代次；数字面板不得误送九键编码，显式读音约束也应用于个人词。语音 CPU 探针不是 APK 录音/准确率验收；只用合成音，下载与推理分离，模型校验、许可和网络隔离不得跳过。iOS 键盘不能直接访问麦克风，不默认云端回退或上传录音。
