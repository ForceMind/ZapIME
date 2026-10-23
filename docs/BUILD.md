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

固定上游原生源码位于 vendor/msime/platforms/{ios,macos,windows}，审计入口见 UPSTREAM_AUDIT.md。ZapIME 自身宿主位于 platforms/{ios,macos,windows}，采用独立标识；不直接将上游品牌包当作 ZapIME 发布。Apple 需要 macOS/Xcode；Windows 系统集成需要 Windows。CI 分别执行三系统共享核心检查和各平台原生构建/桥接检查，实际系统启用及输入证据见 STATUS.md。

Android 开发包还包含逐包 Rust 依赖许可清单。发现无许可文件的新依赖时构建会中止，先核对精确版本及原始来源，不能直接补一个不含版权归属的通用许可证。

Android 离线语音分支由 `build_android.sh` 调用 `build_android_voice.sh`，构建未修改的固定 MSIME LocalAsr 和自有 JNI；构建时校验固定 AAR、两个 ABI 原生库和 nlohmann JSON。NDK 仍固定 28.2.13676358；C++17 静态 libc++、16KiB 对齐及动态依赖允许列表必须通过，许可复制到 assets/voice-licenses。模型 ZIP 单独生成/导入，见 [本地语音](VOICE_INPUT.md)，不把 169MB 权重装进 APK。

`build_android_voice_tests.sh` 构建同开发证书签名、targetPackage 为 IME App 的第二个 instrumentation 包。`android_voice_smoke.py` 只在临时 GitHub emulator 运行：导入固定模型、JNI 识别合成 PCM、测试拒绝权限/AudioRecord 停止，随后从独立测试 App 操作实际语音键盘并读取真实 Editable。执行前会撤销这个合成 App 的录音权限，禁止在个人日常设备运行；正常双轮输入和真实服务退出门禁继续保留。开发签名每个新 runner 独立生成，不能把不同 CI APK 当作可无损覆盖升级的正式签名包，卸载前须导出个人资料。

## 专用设备自动验收

先构建 x86_64 开发包及 `scripts/build_android_tests.sh`，再在专用 API 28 模拟器上运行 `scripts/android_smoke.py <adb-path> <serial>`。该脚本会设置专用设备的默认输入法，不用于日常使用中的设备。测试宿主是独立包 org.zapime.android.test，避免 instrumentation 重启 IME；保存截图、结果和 InputMethodManager 状态以区分产品问题与测试环境问题。

Android 的独立 CI 位于 `.github/workflows/android.yml`，执行两个干净合成资料轮次。`scripts/android_smoke_repeat.py` 只允许临时 GitHub runner 的 emulator serial，清空 ZapIME/测试宿主资料前切回系统输入法，逐轮独立保存结果；本地单轮脚本保留原有资料。候选测试调用指定 Button 的无障碍动作，其他键和焦点走触屏；最终同时核对真实 Editable/组字状态和无障碍正文，不能用它证明候选物理命中稳定性或 ARM 真机表现。
## Apple 原生构建

macOS 和 iOS 原生宿主构建、安装及签名边界见 [APPLE_NATIVE.md](APPLE_NATIVE.md)。Linux 不能执行 Xcode/IMK 构建；CI 的 Apple 原生 job 负责实际编译，不以跨编译共享库替代。


## Windows 原生与独立服务

Windows x64 TSF DLL 构建、COM 边界与安装验收区分见 [WINDOWS_NATIVE.md](WINDOWS_NATIVE.md)。独立 Go/PostgreSQL 服务、容器与备份恢复见 [服务手册](../services/sync/README.md)。这些开发构建均不等于正式发行或生产部署。
