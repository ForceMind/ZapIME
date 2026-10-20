# 源码构建

```sh
git clone --recurse-submodules <有访问权限的 ZapIME 仓库地址>
cd ZapIME
python3 scripts/verify_upstream.py
cargo test --workspace --locked
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
```

工具链由 rust-toolchain.toml 固定。上游源码使用 Git submodule，首次必须初始化，不用 develop 最新值替代锁定提交。构建时可联网获取 Cargo 依赖和校验过的词库；运行时离线。所有生成内容放 target，不把资源缓存和开发密钥提交仓库。

## Android 开发包

Linux/JDK 17、Android API 36、build-tools 35.0.0、NDK 28.2.13676358、zip、Rust Android target：

```sh
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
bash scripts/build_android.sh arm64-v8a
# 模拟器用 x86_64
bash scripts/build_android.sh x86_64
```

原生 Java + aapt2/javac/d8 打包最小宿主，不依赖 Tauri 或 Gradle。输出 target/android-<abi>/ZapIME-0.1.0-dev-<abi>.apk。开发签名保存在 target，非正式发布签名。

安装后在系统输入法设置启用“闪电输入法”，切换并输入 nihao，点击“你好”；测试英文、退格、换应用、密码框。APK 构建与签名通过不代表这些步骤已经验收。

## 其余平台

完整上游原生源码位于 vendor/msime/platforms/{ios,macos,windows}，构建入口见 UPSTREAM_AUDIT.md。它们尚未完成 ZapIME 身份、同步与设置迁移，禁止直接将上游品牌包当作 ZapIME 发布。Apple 需要 macOS/Xcode；Windows 系统集成需要 Windows。当前 CI 先核验三种目标的共享核心编译，原生宿主验证另行列出。

Android 开发包还包含逐包 Rust 依赖许可清单。发现无许可文件的新依赖时构建会中止，先核对精确版本及原始来源，不能直接补一个不含版权归属的通用许可证。

## 专用设备自动验收

先构建 x86_64 开发包及 `scripts/build_android_tests.sh`，再在专用 API 28 模拟器上运行 `scripts/android_smoke.py <adb-path> <serial>`。该脚本会设置专用设备的默认输入法，不用于日常使用中的设备。测试宿主是独立包 org.zapime.android.test，避免 instrumentation 重启 IME；保存截图、结果和 InputMethodManager 状态以区分产品问题与测试环境问题。
