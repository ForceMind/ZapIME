# 自建服务器 + Cloudflare R2 部署

## 新部署入口路线图

**继续施工记录。** 用户要求安装器自动适配 Linux 发行版。平台门禁改为 Linux amd64/arm64 及实际 Docker Compose v2 能力，优先复用已安装运行时。预检识别 apt、dnf、yum、apk、pacman、zypper；未知发行版可预装依赖使用。各包管理器和架构的现场证据分别记录。第二阶段先连通已兼容 OIDC 和实际签名运行包，第三阶段实施迁移演练、停写备份及恢复，上海最终验收使用这套入口。

首轮 `5e5adf4` 已完成原有 Linux 同步和安装器路径。新版离线包在 Docker 导出的清单核对处提前退出。复验对齐 Docker Hub 的标准标签，并将已签名归档摘要与加载后的 Docker 镜像 ID 分开核对，兼容 OCI 索引和 attestations。[首轮隔离验收](https://github.com/ForceMind/ZapIME/actions/runs/45579672493/job/139086249864)

`971820b` 已通过归档签名和离线镜像装载，实际容器的对象存储探针因隔离夹具未创建新版用例的桶而拒绝继续。专用 S3 测试追加独立桶，原真实读写门禁保留。[第二轮隔离验收](https://github.com/ForceMind/ZapIME/actions/runs/45581171539/job/139091320078)

`2775568` 的 Ubuntu amd64 隔离作业通过签名离线首装、S3 读写、OIDC 只读公钥挂载、自动授权、重复安装同一账号、复制库演练、停写成套备份、代理检查失败后拒绝写入、原签名包续跑及原密文解密。后续验证用仅绑定回环的真实 HTTPS 反代核对公网探针协议及实际 APK 签名关联文件，模拟反代断开和恢复；正式证书和上海验收单列。[通过的首装与升级 CI](https://github.com/ForceMind/ZapIME/actions/runs/45582711329/job/139096488470)

2026-11-24，用户确认采用一行启动、完整向导与统一运维。首个切片为已签名预检，后续依次接入首次安装与登录、升级恢复、隔离 Linux 和获准上海服务器的实际验收。实体双机沿用[账号同步验收清单](ANDROID_ACCOUNT_SYNC_IMPLEMENTATION.md#实体双机验收)。

| 阶段 | 可验收交付 |
| --- | --- |
| 1. 下载和预检 | 完整下载后验发布公钥、签名、固定版本、架构与归档内容。Linux 检查系统、磁盘、Docker、端口、DNS、发布源、镜像源和 R2 HTTPS 可达性。错误签名、错误架构、下载失败、端口冲突可分别观察到失败。 |
| 2. 首装与登录 | 签名发布包包含实际版本的 Compose 和镜像摘要。完成 R2 真实读写、HTTPS 和 OIDC。向导核对真实 JWKS、绑定测试账号的 issuer/sub、生成候选 APK 配置并按实际签名关联回跳域。 |
| 3. 运维与升级 | 提供实际版本及运行状态、诊断、备份、升级和恢复入口。先在复制库检查迁移，正式切换前停写、保存数据库与密文成套备份、验证失败后的恢复路径。 |
| 4. 上海服务器及双机 | 用获准的发布域名或同一离线包实测下载、正式测试 HTTPS 和签名关联，最后在两台实体 Android 手机上核对双向资料和修订。 |

### 第一阶段预检

`deploy/bootstrap.sh` 的入口可用 `--source` 从 HTTPS 目录下载，也可用 `--bundle` 读取本地包。准备步骤和入口的版本、公钥文件 SHA-256 取自可信渠道。公网发布前，启动脚本本身也需要提供独立固定摘要。

```bash
python3 deploy/build_preflight_bundle.py --output ./preview/0.1.0-preview.1/amd64 --version 0.1.0-preview.1 --architecture amd64 --private-key /path/to/release-key.pem
bash deploy/bootstrap.sh --bundle ./preview/0.1.0-preview.1/amd64 --version 0.1.0-preview.1 --key-sha256 "$ZAPIME_RELEASE_KEY_SHA256" -- --domain sync.example.com --proxy existing --r2-endpoint https://account.r2.cloudflarestorage.com
```

将独立渠道核验的公钥文件摘要填入 `ZAPIME_RELEASE_KEY_SHA256`。打包目录包含预检 ZIP、分离签名和公开 PEM。arm64 另建对应目录并以 `--architecture arm64` 打包。私钥由签发者保管。远端按 `VERSION/amd64` 或 `VERSION/arm64` 布置这三个文件。`--offline` 跳过 DNS、镜像源和 R2 的网络探针，与 `--bundle` 同用时可全程离线。`--json` 输出机器可读结果。预检退出码为 0 已核对、1 失败、2 尚有待查项。预检只读，不申请 sudo。R2 探针只验证 HTTPS 可达，读写仍由原安装器及后续向导验收。

阶段交接从 `deploy/bootstrap.sh`、`deploy/preflight.py` 与签发脚本接续。`scripts/test_server_preflight.py` 经现有服务器验收入口运行，使用隔离的合成证书、公钥、目录及监听端口。第一阶段已通过。第二阶段接入运行镜像、实际首次配置和 OIDC，再为上海现场准备正式信任入口。

首轮 `7b9989c` 在前序容器使用 TCP 8787 后运行，签名和 HTTPS 下载已执行，四项预检因 8787 尚无法重新绑定失败。复验先在独立 runner 验证真实监听冲突、监听继续存活及连接关闭后的正常重绑，再运行原容器和备份验收。[首轮 CI](https://github.com/ForceMind/ZapIME/actions/runs/45570489655/job/139055732437)

复验 `331b1a7` 在 Ubuntu resolute 的 amd64 GitHub runner 完成六项门禁。实际 HTTPS 单行入口与离线包通过，错误公钥、签名、版本、架构及缺失下载均提前退出。真实端口冲突时原监听生存，连接关闭后可重绑。旧安装器的重复安装、PostgreSQL 与对象存储恢复作业也通过。两架构的合成包已在本机生成并独立验签。arm64 实机、上海连通性、R2 凭据读写与正式身份服务等候各自的现场或发布验收。[通过 CI 与预检输出](https://github.com/ForceMind/ZapIME/actions/runs/45571594367/job/139059309113) · [草稿 PR #127](https://github.com/ForceMind/ZapIME/pull/127)

### 一次命令完成首装

新版入口为 `deploy/bootstrap.sh --install`。同时指定固定发布版本、HTTPS 发布目录或本地包路径，以及从独立渠道确认的公钥 SHA-256。线上首条命令先确认启动器自身的固定摘要，再把这份完整脚本以管理员权限执行。下面的环境变量来自待发布版本的可信清单，域名按最终发布地址填写。

```bash
( set -e; file=$(mktemp); trap 'rm -f -- "$file"' EXIT; curl -fsSL "$ZAPIME_BOOTSTRAP_URL" -o "$file"; printf '%s  %s\n' "$ZAPIME_BOOTSTRAP_SHA256" "$file" | sha256sum -c; sudo bash -c "$(cat "$file")" zapime-install --install --source "$ZAPIME_RELEASE_URL" --version "$ZAPIME_VERSION" --key-sha256 "$ZAPIME_RELEASE_KEY_SHA256" )
```

已有离线包时，已核对摘要的启动器用 `--bundle /absolute/runtime`。入口参数后的 `-- --image-archive /absolute/images.tar` 传给运行安装器。服务器须已有可工作的 Docker 和基础依赖才能全程离线。安装器能在文件可用时按包管理器补齐基础依赖，使用 Linux、架构、Docker Engine 和 Compose v2 的实际能力决定是否继续。既有容器引擎优先复用。发行版仓库缺少所需 Docker 包时，输出缺少的运行时。

首装向导收集域名、代理模式和私有 R2 参数。已有兼容 OIDC 时，可在提示中选择接入，也可在入口的 `--` 后传 `--oidc-config android.json --oidc-jwks jwks.json --oidc-subject-file subject.txt --android-signer SHA256`。subject 由身份服务的管理员界面核对，文件留在服务器。已有资料库加登录时显式传 `--oidc-account UUID`。新库在事务中建立唯一账号映射，重复运行复用持久选择。正式可用性还取决于签发者和客户端发出的实际访问令牌通过 ZapIME 的严格校验。

Docker 只读挂载公开 JWKS。Android 公共配置保存于 `/opt/zapime/oidc/android.json`，签名关联在 `/opt/zapime/oidc/assetlinks.json`。内置 Caddy 提供关联文件，已有反代时由原代理将该精确路径映射到关联文件。安装结束分别报告存储、HTTPS、OIDC 和 App Link。反代需将业务流量送到 `127.0.0.1:8787`。实测浏览器登录及真机同步仍按独立门禁。

发布方用 `deploy/build_runtime_bundle.py` 打包。工具校验三个容器的实际平台及 ID，将源码提交、版本、架构、部署文件和镜像清单签名。在线镜像传仓库摘要引用。离线用 `docker save` 导出三项 `zapime-release/<服务>:<唯一版本>`，签名清单绑定归档 SHA-256 与实际镜像 ID。安装器在私有目录校验归档、核对已经存在的标签归属，再按固定 Compose 镜像运行。

### 更新与中断续跑

`sudo zapime status` 显示已选版本、实际运行镜像、数据库 schema 及未完成的升级。 `sudo zapime doctor` 核对本机健康、公网实例见证及 Android 关联。`sudo zapime update <HTTPS发布目录或绝对包目录> <版本> [离线镜像归档]` 复用首次保存的签名信任。

升级先把数据库快照恢复到独立 Compose 项目，在复制库运行新版本迁移并校验不可变对象。之后冻结写入、停止旧进程、使用旧工具生成成套最终备份。候选服务在冻结状态核对对象、健康和此前已验证的公网路由，通过后切换版本并解除冻结。中断时按 `status` 显示的目标重跑同一签名版本，保留原最终备份。需要改回旧 schema 时，用对应版本及成套备份在空库执行恢复。已重新开放写入后，先保全新写入再选择恢复点。

架构：客户端 → 自有服务器（HTTPS、账号/设备、PostgreSQL 修订索引）→ 私有 R2 Standard 存储桶（端到端密文）。所有对象访问经过自己的服务器；不会向客户端下发 R2 管理密钥或公开存储桶。Android 已提供[手工设备连接](ACCOUNT_CONNECTION.md)，资料同步及配对仍在开发，部署此服务不等于已完成四端同步。

## 首次准备

- 64 位 Ubuntu 22.04 / 24.04 / 26.04 / 26.10，或 Debian 12 / 13；amd64/arm64。建议至少 2 GB 内存用于首次源码构建。安装器会安装缺少的基础工具及 Docker 官方签名 apt 源，不卸载已有容器引擎。
- 一个已经拥有的域名，DNS 指向服务器，入站 80/443 可用。不会替用户购买域名或修改 DNS。若已有 Nginx/Apache 占用端口，使用下面的现有反代模式，不自动停止其他服务。
- 在 Cloudflare 控制台开通 R2，创建 **Standard、私有** 存储桶；保持公共访问关闭，不为 `zapime/v1/` 设置生命周期自动删除。
- 创建只作用于该桶的 **Object Read & Write** R2 凭据，记录 Account ID、Access Key ID、Secret Access Key，以及 bucket 所在 jurisdiction。不是普通 Cloudflare 全局 API Key。

Cloudflare 2026-10-22 官方定价：Standard 每月免费额度为 10 GB-month、100 万次 Class A 和 1,000 万次 Class B，R2 出站免费；超出额度或使用 Infrequent Access 会产生费用。该额度不是自动停费上限，且可能与账号内其它应用共用。请在 Cloudflare 查看用量与账单通知；本服务的每账号 256 MiB 保守配额不能保证整个 Cloudflare 账号始终免费。服务器中转流量仍受自己的服务器套餐计费。

来源：https://developers.cloudflare.com/r2/pricing/ 、 https://developers.cloudflare.com/r2/api/tokens/ 。

日常打字、搜索和使用已下载数据不访问 R2；无新记录的增量查询只查服务器数据库。HTTP 服务增加有界密文缓存，减少重复下载的 R2 读取；完整备份/校验仍直接读持久对象。具体功能、请求估算、备份成本及尚未接入的客户端调度见[同步用量](SYNC_EFFICIENCY.md)。

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

重复运行相同安装命令会保留配置、账号与数据库，先用当前版本备份，再构建并启动指定版本。安装文件保存于 `/opt/zapime/releases/<SHA>`。数据库 schema4 拒绝旧 v1–v3 程序打开，账号密钥身份和 OIDC 主体映射随完整 PostgreSQL 备份保存。升级保留旧内联及对象记录。回滚从升级前备份恢复到全新库。

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

固定e8e016的[CI42350448294](https://github.com/ForceMind/ZapIME/actions/runs/42350448294)也已验证schema3密钥身份登记、保留原登记时间/设备的数据库恢复，以及一键/重复安装、全新DB与对象桶恢复。原日志与实际下载ZIP校验见[服务证据](evidence/manual-sync-20261106/server74-ci/README.md)；这不是后续手动客户端的Android HTTPS或生产R2验收。

此前CI [39793985624](https://github.com/ForceMind/ZapIME/actions/runs/39793985624) 已使用实际 PostgreSQL、独立 S3 兼容对象服务、Docker，完成安装器重跑、全新数据库/另一空桶恢复及真实 Rust AEAD 解密验收（验证 head 3f8251f7ba0fc6ba0a75c09bf804dc4616c4c966）。兼容协议验收不等于已访问用户 Cloudflare 账号。没有用户 R2 凭据、域名和服务器 SSH 时，不宣称已完成真实 R2、公网证书或用户服务器部署。具体 CI 和结果记录见 STATUS.md。
