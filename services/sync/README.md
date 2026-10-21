# ZapIME 独立密文同步服务

独立开发的 Go/PostgreSQL 服务，不复制 `msime-cloud` 实现。当前是 M3 的服务端切片：密文持久化、事务修订、幂等重试、冲突返回、设备令牌、撤销、账号隔离与数据库迁移。不是已经接通四端的云产品。

当前账号由管理员 CLI 创建，账号 UUID 贯穿设备；尚无密码登录、邮件验证、图形账号页、带核对码的新设备配对、客户端密钥传输或四端同步客户端。设备令牌不会解开端到端密文。客户端仍须持有根密钥或恢复资料；服务端不接收这些材料。

## 本地开发

需要 Go 1.27.3+、PostgreSQL 16。使用专门的开发数据库，设置连接信息后运行：

```sh
cd services/sync
export DATABASE_URL='postgres://zapime@127.0.0.1:5432/zapime?sslmode=disable'
# PGPASSWORD 通过进程环境提供，不写入源码或 shell 历史。
go run ./cmd/zapime-sync
```

默认仅监听 `127.0.0.1:8787`。`/healthz` 只返回协议版本和数据库就绪状态；普通日志不打印请求正文、授权头、连接串或设备令牌。

用另一个终端显式创建账号和首个 owner 设备：

```sh
go run ./cmd/zapime-sync provision '我的首个设备'
```

命令向标准输出显示一次账号 ID、设备 ID、30 天有效的高熵令牌和到期时间。请将输出保存在受保护的位置，不要粘贴到 Issue、日志或普通数据导出。数据库只保存令牌 SHA-256，既不保存令牌明文，也不保存端到端根密钥。

初始 owner 丢失或令牌到期时，持有数据库管理权限的操作者可以执行 `zapime-sync recover-device <设备 UUID>`，使旧令牌立即失效并重发访问凭据。此命令可重新启用指定设备，属于显式管理员恢复；**不会恢复解密密钥**，也没有通过网络暴露。恢复 E2EE 数据仍需要用户自持恢复包和恢复码。

## Docker Compose 与 HTTPS

在本目录创建未提交的 `.env`，设置自己的强随机 `POSTGRES_PASSWORD`。不要使用文档中的占位内容作真实密码。然后运行：

```sh
docker compose up -d --build
docker compose exec sync zapime-sync provision '我的首个设备'
```

`sync` 为无 root、只读文件系统容器；数据库端口不发布到宿主机，API 只发布在宿主回环地址。容器携带自身 GPL 与所有运行时 Go 模块的许可清单。服务不包含生产访问凭据。

如已拥有域名并将 DNS 指向服务器，设置 `.env` 中 `ZAPIME_HOST` 为该域名，然后运行 `docker compose --profile https up -d`，由 Caddy 获取证书并提供 HTTPS。未配置自有域名时不宣称已获得生产 HTTPS，也不假定 zapime.com/zapime.app 已归项目所有。生产部署还需防火墙、外层速率限制、监控、数据库备份及密钥恢复演练；此切片尚未在用户服务器部署。

Compose 内数据库连接走独立 Docker 网络且不发布端口；跨主机 PostgreSQL 必须配置证书校验 TLS，不能把示例中的 `sslmode=disable` 用于公网数据库。

## 测试

```sh
export ZAPIME_TEST_DATABASE_URL='postgres://postgres@127.0.0.1:5432/zapime_test?sslmode=disable'
export ZAPIME_REQUIRE_DATABASE=1
go test -race -count=1 -v ./...
go vet ./...
```

没有测试数据库时普通运行只执行纯协议测试并显式跳过 PostgreSQL 测试；CI 设置 `ZAPIME_REQUIRE_DATABASE=1`，缺少数据库即失败。测试使用实际 PostgreSQL，不用内存仓库代替事务验证，所有账号和内容均为合成数据。

## 备份与新服务器恢复

数据库备份包含设备元数据、令牌摘要、密文、修订和操作历史，仍需以私密文件管理；它不包含 E2EE 根密钥。

```sh
bash backup.sh /absolute/path/zapime-backup.dump
```

新服务器先只启动空数据库 `docker compose up -d db`，在启动 sync 自动建表前运行：

```sh
bash restore.sh /absolute/path/zapime-backup.dump
docker compose up -d sync
```

恢复脚本拒绝已有 public 表的数据库，不执行 DROP/--clean；`pg_restore` 使用单事务，失败回滚。备份脚本使用私密临时文件和不覆盖目标的原子链接。生产恢复还应轮换设备凭据，并由客户端重新验证密文及恢复资料。

这条路径恢复的是同一账号/修订历史；空服务器上的个人数据重建、所有设备丢失后的客户端恢复以及四端重新配对尚待端到端验收。

协议与边界见 [SYNC_PROTOCOL.md](../../docs/SYNC_PROTOCOL.md)。
