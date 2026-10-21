# Apple 原生开发宿主

本目录对应真实 InputMethodKit 宿主和 iOS 自定义键盘扩展。当前阶段为离线、不学习的开发预览，不包含个人数据管理、同步、云剪贴板、签名发行或公证。编译及桥接测试和“系统启用后在其他 App 实际输入”是不同验收项，最新结果见 STATUS.md。

## macOS

需要 macOS 13+、Xcode 命令行工具、Rust 1.97.1、Python 3。运行：

```sh
bash scripts/build_macos.sh
```

输出 `target/macos/ZapIME.app`，只包含构建机器架构，使用 ad-hoc 开发签名，不能视作公证发行包。将该 App 复制到当前用户 `~/Library/Input Methods/`，退出并重新登录后，在系统设置的键盘输入源中添加闪电输入法。不要覆盖同名已安装版本前未经备份就删除资料。删除 App 可卸载宿主，资料目录 `~/Library/Application Support/org.zapime.inputmethod/` 单独保留。

全拼字母组字、空格选择首候选、数字 1–9 选词、回车提交原编码、Esc 取消、退格修改组字，Shift+Space 切换英文直通。候选浮窗暂为文字列表，未实现鼠标选词和翻页。默认关闭学习与个人数据，安全输入由系统边界处理。本版本不调用网络、剪贴板、辅助功能或语音权限。

Bundle ID `org.zapime.inputmethod.ZapIME`，输入源 `org.zapime.inputmethod.ZapIME.Hans`。连接名遵守上游记录的 `<BundleID>_Connection` 约束；不会注册水杉的输入源或复用其资料目录。

系统输入实验见 [PR #12](https://github.com/ForceMind/ZapIME/pull/12)。它只在临时 GitHub runner 中注册测试输入源，对独立 Cocoa 编辑器派发合成按键；拒绝在本地用户会话运行或覆盖现有安装。首轮 macOS 15 Intel CI 编译、注册/启用成功，但 TIS 选择返回 -50，未到达输入断言。该失败不计为系统输入通过，后续轮次结果以 STATUS.md 为准。固定上游也记录了新输入源登记和当前登录会话缓存的限制；不能把注册返回 noErr 当成可以选择或已经完成输入。

## iOS

需要 Xcode、XcodeGen、Rust 与 Python。运行：

```sh
bash scripts/build_ios.sh simulator
```

输出 `target/ios-xcode/Build/Products/Debug-iphonesimulator/ZapIME.app`，包含真正 `com.apple.keyboard-service` 扩展。生成的 Xcode 工程不提交仓库。CI 自动选择已有 iPhone 模拟器，实际运行打包词库的 Swift/Rust 桥接、专用线程和真实键盘控制器的文本代理通知重入测试（3 项）；这些测试不代表已在系统设置中启用键盘或完成其他 App 的输入验收。模拟器脚本先执行 build-for-testing，CI 再 test-without-building，结果按 run/attempt 唯一路径保存。

```sh
xcrun simctl install booted target/ios-xcode/Build/Products/Debug-iphonesimulator/ZapIME.app
xcrun simctl launch booted org.zapime.ios
```

在模拟器/设备系统设置 → 通用 → 键盘 → 键盘 → 添加新键盘中启用闪电输入法。无需允许完全访问；离线资源随扩展打包。系统安全字段和部分不允许第三方键盘的 App 会使用系统键盘，不能绕过。

`bash scripts/build_ios.sh device` 只检查设备架构的未分发签名构建。真机安装需在生成工程中配置自己的开发团队和签名身份，通过 Xcode 的正规签名流程运行；脚本不自动注册 Apple 账号、申请证书或发布到 TestFlight。

App ID `org.zapime.ios`，扩展 ID `org.zapime.ios.keyboard`。当前扩展资料位于自己的沙盒，不启用 App Group，主 App 和扩展暂不共享个人资料；后续 M2 将加入受控共享容器和密钥管理。

## 共享 ABI 和许可

`platforms/common/zapime.h` 版本 1 采用有长度的 UTF-8 JSON。句柄只在创建线程有效，缓冲区必须原样交还一次；不能跨线程使用会话，也不能把 serial DispatchQueue 等同于固定操作系统线程。iOS 用专用 Thread 驱动；焦点代次不匹配的迟到结果不会上屏。macOS 使用 IMK 本地同步调用，不等待网络。

引擎、词库来源与 GPL/第三方声明保留。构建产物包含 LICENSE、third_party 和当前目标非开发依赖许可清单；不包含上游账号、社区、语音服务或品牌素材。新宿主参考了固定 MSIME 的 IMK 生命周期和 iOS 扩展配置约束，未改写 vendor 子模块。
