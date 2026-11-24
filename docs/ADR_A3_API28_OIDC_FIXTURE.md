# ADR · A3 一次性 API28 OIDC 回跳验收

2026-11-24。用户已明确批准 A3 独立 GitHub 作业在全新 API28 模拟器中添加本轮合成 CA 和 `.test` 域名映射，结束后核对清理。Astra 已审查独立验收边界。原 [ADR-023](ADR_023_ANDROID_SYNC_HTTPS_CI.md) 的适用作业和脚本保持独立。

- 仅隔离 GitHub 作业和本轮全新 AOSP API28 `userdebug`、`writable-system`、`wipe-data`、`no-snapshot` 模拟器。写入前核对工作流、数字 run/attempt、专用 emulator 序列、qemu、API28、root 与 SELinux Enforcing。
- 只在工作副本中为两个固定 `.test` 域做本机映射，使用本轮独有的 443 反向端口和只绑定 runner 回环地址的真实 OIDC 提供方。确认端口、域名和 CA 路径未被占用。原始 hosts、CA 哈希清单和 reverse 清单在事务前后比较；清理逐项只撤本轮内容。
- 测试 APK 单独签名，沿用平台 TLS 与域名校验，不使用测试信任配置。固定验证前、移除 CA 后都由 App 发起 TLS 请求，并在域名和映射可达时观察证书拒绝。测试证书私钥、授权码、令牌及页面内容不进入参数、日志或 artifact。
- 两项成功分别记录。第一项是系统从正确 HTTPS 域取得本轮 `assetlinks.json` 后，把实际 APK、签名与回跳域验证为关联。第二项是实际系统浏览器从真实合成账号授权页面通过 HTTPS 回跳，测试 App 校验本次 `state` 和授权码、使用 PKCE 换令牌，同一 Go 校验代码验证服务签发的专用令牌。人工选择回跳处理器、由 ADB 注入回跳或单测通过都不能代替第二项。
- 报告只记录固定原因码、步进状态、数量、源码与公开指纹。登录步骤核对资料传输请求为零。任何失败均运行清理并保留原失败。此验收范围是隔离测试 APK，产品 App 接入、绑定服务、设备凭据领取和原厂公网信任分别验证。
