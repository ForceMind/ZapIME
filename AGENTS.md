# ZapIME 工程约定

- 首先阅读 docs/DEVELOPMENT_PLAN.md、docs/STATUS.md、docs/DECISIONS.md。仓库按用户 2026-10-22 明确指令保持 Public；不要擅自修改可见性。
- vendor/msime 固定提交，禁止未审计更新；上游版权、许可证和 NOTICE 原样保留。修改该子模块前阅读其中 AGENTS.md。不要复制 msime-cloud 实现。
- 输入线程不等网络。个人数据默认本地；云上传需显式开启。密码及 NO_PERSONALIZED_LEARNING 字段不学习。测试数据必须合成。
- 工程默认 Rust 1.97.1；从根运行 cargo test --workspace、cargo fmt --all --check、cargo clippy --workspace --all-targets -- -D warnings。
- 同步准备层保留不可变操作快照和首次密文请求；本地 revision、服务器 revision、拉取 cursor 分开。上传确认不得覆盖后续编辑或推进拉取 cursor；远端应用和冲突解决尚未实现。
- Go 服务使用 go.mod 固定工具链。在 services/sync 运行 go test -race -count=1 -v ./...；事务验收必须配置真实 PostgreSQL 的 ZAPIME_TEST_DATABASE_URL 和 ZAPIME_REQUIRE_DATABASE=1。显式 SKIP 不等于数据库测试通过；容器灾备入口为 scripts/sync_service_smoke.py。
- Android 原生构建：ANDROID_SDK_ROOT 指向 API 36 / build-tools 35 / NDK 28.2.13676358；bash scripts/build_android.sh arm64-v8a。APK 构建通过不等于系统输入验证。
- Git submodule update --init 后 python3 scripts/verify_upstream.py 核验来源。不要提交 target、凭据、私钥或真实用户文本。
- 每个独立切片用分支和 PR，禁止强推。审查当前差异、跑对应检查，按仓库规则合并。
- docs/STATUS.md 区分源码、编译、模拟器、真机及签名证据；更新时记录时间与可追溯提交。不得用定时任务代替开发。
