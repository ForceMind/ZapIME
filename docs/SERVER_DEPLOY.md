# 自建服务器 + Cloudflare R2 部署

架构：客户端 → 自有服务器（HTTPS、账号/设备、PostgreSQL 修订索引）→ 私有 R2 Standard 存储桶（端到端密文）。所有对象访问经过自己的服务器；不会向客户端下发 R2 管理密钥或公开存储桶。输入法客户端接入及配对仍在开发，部署此服务不等于已完成四端同步。

## 首次准备

- 64 位 Ubuntu 22.04 / 24.04 / 26.04 / 26.10，或 Debian 12 / 13；amd64/arm64。建议至少 2 GB 内存用于首次源码构建。安装器会安装缺少的基础工具及 Docker 官方签名 apt 源，不卸载已有容器引擎。
- 一个已经拥有的域名，DNS 指向服务器，入站 80/443 可用。不会替用户购买域名或修改 DNS。若已有 Nginx/Apache 占用端口，使用下面的现有反代模式，不自动停止其他服务。
- 在 Cloudflare 控制台开通 R2，创建 **Standard、私有** 存储桶；保持公共访问关闭，不为 `zapime/v1/` 设置生命周期自动删除。
- 创建只作用于该桶的 **Object Read & Write** R2 凭据，记录 Account ID、Access Key ID、Secret Access Key，以及 bucket 所在 jurisdiction。不是普通 Cloudflare 全局 API Key。

Cloudflare 2026-10-22 官方定价：Standard 每月免费额度为 10 GB-month、100 万次 Class A 和 1,000 万次 Class B，R2 出站免费；超出额度或使用 Infrequent Access 会产生费用。该额度不是自动停费上限，且可能与账号内其它应用共用。请在 Cloudflare 查看用量与账单通知；本服务的每账号 256 MiB 保守配额不能保证整个 Cloudflare 账号始终免费。服务器中转流量仍受自己的服务器套餐计费。

来源：https://developers.cloudflare.com/r2/pricing/ 、 https://developers.cloudflare.com/r2/api/tokens/ 。

## 一条命令安装

在服务器 SSH 终端运行；首次按提示输入域名、R2 账号/桶和读写凭据。敏感输入不回显，不放进命令行参数或聊天。

```sh
curl -fsSL https://raw.githubusercontent.com/ForceMind/ZapIME/main/deploy/install.sh | sudo bash
```

默认取得 main 的一次精确提交并保存实际 SHA；生产可将下载 URL 中的 main 换成审核过的 SHA，并传 `--ref` 同一 SHA，避免移动分支产生版本差异：

```sh
curl -fsSL https://raw.githubusercontent.com/ForceMind/ZapIME/COMMIT_SHA/deploy/install.sh | sudo bash -s -- --ref COMMIT_SHA
```

安装器检查 R2 真实写/读/删除探针，启动 PostgreSQL 和服务，创建首个 owner 设备，配置 Caddy HTTPS，并验证公网 `/healthz` 的协议响应后才报告 HTTPS 成功。不会创建真实用户端到端密钥，也不会把访问令牌冒充解密密钥。

已有反向代理时：

```sh
curl -fsSL https://raw.githubusercontent.com/ForceMind/ZapIME/main/deploy/install.sh | sudo bash -s -- --existing-proxy
```

将现有代理的 HTTPS 上游配置为 `127.0.0.1:8787`；这个模式不启动 Caddy，不声称已经验证公网 HTTPS。数据库端口不发布，API 只发布到回环地址；只允许 HTTPS 对公网提供服务。

## 管理、升级与密钥

```sh
sudo zapime status
sudo zapime check-storage
sudo zapime logs
sudo zapime backup
sudo zapime verify-objects
```

配置位于 `/opt/zapime/config.env`，首次设备访问凭据位于 `/opt/zapime/owner.json`，均只供 root 读取。恢复安装沿用旧设备凭据，不创建新的 owner 文件。令牌 30 天到期，必要时用 `sudo zapime recover-device <设备 UUID>` 显式重发；输出需私密保存，该操作不恢复 E2EE 密钥。

修改错误/失效的 R2 设置时使用 `sudoedit /opt/zapime/config.env`，只改 `ZAPIME_S3_*`；**保留 POSTGRES_PASSWORD**，不能删掉配置让安装器重新随机生成数据库密码。之后重新执行安装命令。不要执行 `docker compose config` 将凭据完整打印到普通日志；可用 `config --quiet` 校验。

重复运行相同安装命令会保留配置、账号与数据库，先用当前版本备份，再构建并启动指定版本。安装文件保存于 `/opt/zapime/releases/<SHA>`；没有 `down --volumes` 或自动清空用户资料。数据库 schema v2 拒绝旧 v1 程序降级，不能把切换旧镜像当作数据库回滚；需要时从升级前备份恢复到全新库。

支持 systemd 时自动安装每日备份 timer。备份存放 `/opt/zapime/backups`，不自动删除旧归档；应监控磁盘并将成套备份复制到自己控制的异地位置。Docker 普通日志按 10 MB × 3 轮转，不记录正文/令牌。当前是可部署的开发服务，尚未完成第三方安全审计、外层限流与生产监控。

## 全新服务器 / 更换 R2 存储桶

`zapime backup` 产生一对私密文件：

1. `日期.dump`：一致的 PostgreSQL 账号、设备摘要、记录、操作与修订快照。
2. `日期.dump.objects.tar`：随后导出的不可变密文对象超集，采用标准 tar。两者一起保存；客户端另行保管 E2EE 恢复包/恢复码。

先完成数据库快照，再导出追加写对象；当前没有后台对象清理，所以并发新增不会让快照引用的对象漏出归档。失败不发布完整 dump；不要把 `.incoming.*` 当成成功备份。

将这两个文件放到**全新服务器**，准备同一或另一家 S3 兼容存储（安装器提示默认 R2，其他 S3 可在受保护配置中设置 endpoint/region），执行：

```sh
curl -fsSL https://raw.githubusercontent.com/ForceMind/ZapIME/main/deploy/install.sh | sudo bash -s -- --restore /absolute/path/backup.dump
```

安装器只向空库恢复，拒绝已有运行实例的覆盖恢复；导入对象后逐个验证所有数据库引用，再启动服务。对象导入可幂等重试，不覆盖现存但哈希错误的对象。若数据库已恢复、网络中断导致对象导入未完成，保持服务停止，用 `sudo zapime restore-objects /absolute/path/backup.dump.objects.tar` 重试并验证，再 `sudo zapime start`；不会重新覆盖数据库。

旧版仅含数据库的备份并非 R2 完整备份；仍可按 PostgreSQL 原生流程恢复内联数据，但新恢复脚本要求成套 sidecar。R2 写成功而数据库提交失败时可能遗留无引用密文对象；当前不自动回收，以免误删历史。缺失或损坏对象返回失败，不能当作空同步页推进游标。

## 验证范围

CI [39793985624](https://github.com/ForceMind/ZapIME/actions/runs/39793985624) 已使用实际 PostgreSQL、独立 S3 兼容对象服务、Docker，完成安装器重跑、全新数据库/另一空桶恢复及真实 Rust AEAD 解密验收（验证 head 3f8251f7ba0fc6ba0a75c09bf804dc4616c4c966）。兼容协议验收不等于已访问用户 Cloudflare 账号。没有用户 R2 凭据、域名和服务器 SSH 时，不宣称已完成真实 R2、公网证书或用户服务器部署。具体 CI 和结果记录见 STATUS.md。
