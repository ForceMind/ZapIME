# ZapIME 独立密文同步服务

独立开发的 Go/PostgreSQL 服务，不复制 `msime-cloud` 实现。当前是 M3 的服务端切片：密文持久化、事务修订、幂等重试、冲突返回、设备令牌、撤销、账号隔离、只创建一次的公开密钥身份与数据库迁移。不是已经接通四端的云产品。

当前账号由管理员 CLI 创建，账号 UUID 贯穿设备；尚无密码登录、邮件验证、带核对码的新设备配对、客户端密钥传输或四端数据同步。Android候选源码有[手动设备连接页](../../docs/ACCOUNT_CONNECTION.md)，通过新`GET /v1/session`验证账号/设备/角色/到期时间并加密保存连接，尚不交换个人资料。设备令牌不会解开端到端密文。客户端仍须持有根密钥或恢复资料；服务端不接收这些材料。

## 公开密钥身份与升级

新增 `GET/PUT /v1/key-descriptor`：owner在空历史账号上明确登记一次 `{version:1,key_id,material_sha256}`；相同描述符重试幂等，不同描述符拒绝。member只能读取和核对。描述符不含根密钥、恢复码或恢复包，不会自动创建客户端材料或配对。登记后的新Push必须使用同key ID，旧API不能绕过登记写入另一套key。已有历史但没有描述符的账号保留旧v1操作兼容，但不能自动登记新根；legacy迁移、密钥轮换和客户端HTTP编排仍未完成。

服务端数据库升级为 schema4，旧 v1–v3 二进制拒绝打开。升级前保存成套备份。R2 对象 `storage_version=2`、客户端 SQLite 和 HTTP 协议 v1 保持原格式。密钥身份接口见[密钥身份说明](../../docs/SYNC_KEY_DESCRIPTOR.md)。

A3 预置身份由管理员在服务运行环境内用 `zapime-sync bind-oidc-subject <account-uuid> <issuer> <subject>` 绑定到现有账号。参数是事先核实的签发者与主体，邮箱仅用于展示。相同绑定允许重试，改绑已关联主体会拒绝。映射表随 PostgreSQL 完整备份保存。[A3 执行记录](../../docs/ANDROID_ACCOUNT_SYNC_IMPLEMENTATION.md)

`GET /v1/account-login` 是启用后才开放的账号身份查询，返回协议号和既有账号 UUID。启用时一并设置 `ZAPIME_OIDC_ISSUER`、`ZAPIME_OIDC_AUDIENCE`、`ZAPIME_OIDC_CLIENT_ID`、`ZAPIME_OIDC_JWKS_FILE`。密钥文件只包含部署者审核的公开 JWK，配置变更或签名密钥轮换后重启服务。访问令牌须有专用受众、`zapime.device_enrollment` 范围和至多五分钟有效期。资料接口继续使用独立设备凭据。

### 设备登记协议

账号查询同时返回 `subject_fingerprint`，客户端据此将待领取凭据绑定到经过服务核实的主体。SHA-256 输入依次为 `zapime-account-login-v1`、issuer、subject、受众和账号 UUID，各字段先写四字节无符号大端 UTF-8 长度，再写字段字节。响应只返回小写十六进制摘要。

| 接口 | 授权 | 请求和结果 |
| --- | --- | --- |
| `POST /v1/device-enrollment` | 绑定已有账号的短时 OIDC 访问令牌 | 提交 `enrollment_id`、`account_id`、`name`、`deadline`（Unix 秒）、`public_key`、`token_sha256`、`signature`，返回相同 ID、期限及完整十六进制 `fingerprint`。同一令牌 ID 只能登记同一完整请求。 |
| `POST /v1/device-enrollment/approve` | 同账号当前有效的 owner 设备凭据 | 核对并明确批准 `enrollment_id` 和完整的 `fingerprint`，返回 204。批准与该 owner 当前凭据绑定。 |
| `POST /v1/device-enrollment/claim` | 同一 issuer、subject 和受众的有效访问令牌及原设备私钥 | 提交 `enrollment_id`、`account_id`、`signature`。等待 owner 时返回 409 `enrollment_pending`，成功时返回与 `/v1/session` 相同的公开设备身份。 |

客户端先以密码学随机源生成格式为 `zap1_` 加 32 字节无填充 Base64URL 的凭据，安全保存后才申请。申请使用 P-256 公钥的 SPKI DER。公钥、凭据 SHA-256 和 ASN.1 DER 签名采用无填充 Base64URL。登记上限为每账号八个未领取的有效请求，期限不超过十分钟。设备激活占用既有 32 个有效设备限额，有效期为 30 天。被撤销的批准、轮换过的批准凭据或已撤销的申请设备均不能用旧请求激活。

每个签名前消息的字段均以四字节无符号大端长度开头。文本使用 UTF-8，指纹、凭据摘要、公钥和访问令牌摘要按原字节拼接。指纹是字段 `zapime-enrollment-v1`、issuer、受众、账号、申请 ID、设备名、十进制 deadline、公钥 DER、凭据摘要依序编码的 SHA-256。签名输入是用途 `zapime-enrollment-begin-v1` 或 `zapime-enrollment-claim-v1`、原始指纹、当前访问令牌的 SHA-256 三个字段；使用 `SHA256withECDSA`。申请设备自己计算并核对返回的完整指纹，再交给 owner 核对。领取发起时可以使用同一身份新签发的访问令牌。

服务器原子激活凭据摘要并消费批准，设备 ID 等于申请 ID。回执丢失时可用原有本机凭据请求 `GET /v1/session`，核对账号、设备与 `member` 角色。领用响应只包含公开身份。账号查询和设备登记均无需端到端材料或密文资料。

## 一键服务器部署与 R2

新增私有 S3/R2 密文对象后端及一条命令的服务器安装入口，操作见 [SERVER_DEPLOY.md](../../docs/SERVER_DEPLOY.md)。数据库仅保存新对象的账号/哈希引用；旧内联记录兼容读取。使用官方 AWS SDK v2 的 SigV4、条件创建和逐对象 SHA-256 校验；R2 不可用时同步失败，不返回空页假成功。

## 本地开发

需要 Go 1.27.4+、PostgreSQL 16（2026-11-10 安全修复版本，包含 net/http 的 GO-2026-6859 / CVE-2026-97054 修复）。Docker 镜像构建与 GitHub CI 使用同一最低 Go 版本。使用专门的开发数据库，设置连接信息后运行：

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

PostgreSQL 模式的数据库备份包含账号的密钥身份描述符、设备元数据、令牌摘要、密文、修订和操作历史；R2 模式新正文位于对象桶，必须同时保存 .objects.tar sidecar，仍需以私密文件管理；它不包含 E2EE 根密钥。

```sh
bash backup.sh /absolute/path/zapime-backup.dump
```

备份命令会同时生成同名 .objects.tar，必须成套保存。恢复时 sync 必须停止。新服务器先只启动空数据库 `docker compose up -d db`，在启动 sync 自动建表前运行：

```sh
bash restore.sh /absolute/path/zapime-backup.dump
docker compose up -d sync
```

恢复脚本拒绝已有 public 表的数据库，不执行 DROP/--clean；`pg_restore` 使用单事务，失败回滚。备份脚本使用私密临时文件和不覆盖目标的原子链接。生产恢复还应轮换设备凭据，并由客户端重新验证密文及恢复资料。

这条路径恢复的是同一账号/修订历史；空服务器上的个人数据重建、所有设备丢失后的客户端恢复以及四端重新配对尚待端到端验收。

协议与边界见 [SYNC_PROTOCOL.md](../../docs/SYNC_PROTOCOL.md)。
