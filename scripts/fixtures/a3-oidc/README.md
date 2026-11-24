# A3 主机 OIDC 验收夹具

将本目录的 `package.json`、`package-lock.json` 和 `probe.mjs` 复制到仓库下忽略的 `target/a3-oidc-run`。使用 Node 26 和可用的 OpenSSL，依次运行：

```text
npm ci --prefix target/a3-oidc-run --ignore-scripts --no-audit --no-fund
node target/a3-oidc-run/probe.mjs
```

`probe.mjs` 可接收 OpenSSL 可执行文件路径。只在回环地址监听，使用合成凭据、内存状态和临时本地证书，正常退出清理本轮证书和私钥。TLS 信任及域名解析限定在测试进程。结果只输出固定测试标记。Android 系统回跳、Go 服务身份绑定、设备凭据领取分别验收。

从 `services/sync` 用固定 Go 工具链运行 `go test -c -o ../../target/a3-oidc-run/oidc-auth.test ./internal/oidcauth`，然后从仓库根运行 `node target/a3-oidc-run/probe.mjs openssl target/a3-oidc-run/oidc-auth.test`。Windows 使用本机 OpenSSL 的可执行文件路径和 `.exe` 测试文件。真实提供方令牌和公钥从标准输入交给 Go 校验代码，检查正确令牌、错误受众与 ID token 拒绝。该验证不访问 PostgreSQL。
