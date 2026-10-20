# ZapIME 工程约定

- 首先阅读 docs/DEVELOPMENT_PLAN.md、docs/STATUS.md、docs/DECISIONS.md。仓库必须保持 Private。
- vendor/msime 固定提交，禁止未审计更新；上游版权、许可证和 NOTICE 原样保留。修改该子模块前阅读其中 AGENTS.md。不要复制 msime-cloud 实现。
- 输入线程不等网络。个人数据默认本地；云上传需显式开启。密码及 NO_PERSONALIZED_LEARNING 字段不学习。测试数据必须合成。
- 工程默认 Rust 1.97.1；从根运行 cargo test --workspace、cargo fmt --all --check、cargo clippy --workspace --all-targets -- -D warnings。
- Android 原生构建：ANDROID_SDK_ROOT 指向 API 36 / build-tools 35 / NDK 28.2.13676358；bash scripts/build_android.sh arm64-v8a。APK 构建通过不等于系统输入验证。
- Git submodule update --init 后 python3 scripts/verify_upstream.py 核验来源。不要提交 target、凭据、私钥或真实用户文本。
- 每个独立切片用分支和 PR，禁止强推。审查当前差异、跑对应检查，按仓库规则合并。
- docs/STATUS.md 区分源码、编译、模拟器、真机及签名证据；更新时记录时间与可追溯提交。不得用定时任务代替开发。
