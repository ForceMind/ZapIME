# 上游源码与构建审计

核验日期：2026-10-20。来源提交见 third_party/upstream-lock.json。

## 本仓基线

ForceMind/ZapIME main = 076961750bab1657b8a98c5e019daa234861a54a，Private、未保护 main，只有四份文档。无既有产品源码、CI 或二进制。通过 GitHub 连接复核；私有仓库终端 clone 缺凭据，使用连接提交，不改变可见性。

## MSIME

实际下载 develop 的 97d652a49cabb0887355b84186af2aa9032d3814。Rust 1.97.1，workspace 最低声明 1.89。完整树 10958 个目录/文件条目，根无 .gitmodules。engine 的 neural 推理依赖固定 chinese-ime-lm 15caab4；运行时 engine 无 HTTP 客户端。client-services/host-api 另有上游账号与联网业务，不应全部默认启用。

- engine：组词、词典、学习与 Session；input-runtime：焦点、分页与会话编排；host-api：C ABI；client-core/base/providers/services 分层明确。
- Android：platforms/android/java 原生 Java InputMethodService；正式入口 build-apk.sh，API 36、build-tools 35、NDK 28.2.13676358。Gradle + AndroidX/Material + JNI 原生库；build-client-apk.sh 是开发 Tauri 合包。ZapIME 首包独立最小宿主直接调用 engine，不携带整个上游账号/语音/社区服务图。
- iOS：SwiftUI App + UIInputViewController；XcodeGen project.yml；build-app.sh 真实调用 xcodebuild。模拟器无签名构建也需要 macOS/Xcode，真机另需账号证书、App Group 与设备。
- macOS：InputMethodKit，CMake + Rust；原生输入源与设置应用分离。需要 macOS SDK、输入源注册、签名与公证验证。
- Windows：TSF DLL + 独立 Server，原生 WinUI 3 设置；Build-Client.ps1，需要 MSVC/vcpkg/WinUI 工具链。存在 MinGW 交叉构建入口，但它不等于 Windows 系统输入验证。

## chinese-ime-lm

实际下载并阅读最新 4e745be72f1c49e9c7b5dadea819fbb1fcda0e13；Cargo 实际拉取并编译 15caab4 参考推理。只在 reference/ 适用 Apache，训练工具根许可 GPL，权重另有授权与语料署名。首包不下载权重，避免未经评测直接启用重排。

## msime-cloud

GitHub 读取 main 85fe4cebe474abbf03e662d95bbcc0b1ed38f1ee 的完整树、go.mod 和 clipboard_e2e.go 前段。Go 1.26.9、pgx/PostgreSQL、设备 key wrap、key rotation、撤销列表和密文大小边界。无覆盖第一方代码的根许可；不落盘或复制实现。ZapIME 后端将独立实现。

## 资源

实际获取 dict-v2.0.24 manifest 和锁定拼音/英文资源，逐文件大小/SHA-256 校验。manifest 声明排除未授权输入；首包不使用未授权完整词库模式。生产词库与人工测试小词库严格区分。

## 当前环境

Linux x86_64；已有 Java 17、GCC。自主准备 Rust 1.97.1、Android SDK/NDK。没有 macOS/Xcode、Windows GUI/MSVC、用户签名证书或三星真机；不伪造三端原生构建和真机结果。Android 模拟器没有 /dev/kvm，尝试软件模拟，结果单独记录。
