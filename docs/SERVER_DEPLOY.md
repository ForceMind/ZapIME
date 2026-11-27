# 自建服务器 + Cloudflare R2 部署

## 新部署入口路线图

### 同一向导安装后台

2026-11-27 已确认实施。首装从一条命令进入 SSH 向导，复用现有 Linux 运行时检测、R2 探针、OIDC 绑定、HTTPS 实例确认和持久运维命令。向导同时配置用户中心、首位管理员和受保护的托管主密钥。上海服务器由用户亲自执行。

| 步骤 | 验收条件 | 进度 |
| --- | --- | --- |
| 接通同一向导 | 选 Caddy 或 Tunnel，输入 R2 和身份提供方信息，完成后台配置并保留已有密钥与配置 | 已接通，待空白 Linux 实测 |
| 空白 Linux 隔离验收 | 单行执行，在同一终端完成配置，走 HTTPS、OIDC 回调与首位管理员实际登录。重复执行时保留安装、账号和同一密钥 | 待验收 |
| 交付 | 在新分支固化源码，回读安装入口与摘要，交付已验收提交的一条命令 | 待验收 |

操作者在自己的 Cloudflare 和身份服务中准备域名、私有 R2 桶、读写凭据和兼容的 OIDC 客户端。向导计算回调地址，读取公开身份配置，引导绑定首位管理员。终端只需要运行安装命令，敏感值按提示输入。

- 选择 Caddy 时，由安装器启动本机反代，使用域名和入站 80/443。
- 选择 Tunnel 时，在 Cloudflare 将同步主机名映射到宿主机 `http://127.0.0.1:8787`。已有连接器可以复用。空白机可在向导中隐藏输入远程托管的连接器 token，安装器用固定摘要的官方镜像启动 `zapime-tunnel`。token 由 root 和连接器读取，位于 `/opt/zapime/tunnel/token`，更换服务器时使用 Cloudflare 提供的正式凭据。[Cloudflare 连接器凭据说明](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/run-parameters/#token-file)
- 向导会给出用户中心和管理后台的 HTTPS 地址。首次从后台登录并看到用户目录后，回到同一 SSH 窗口继续。向导同时检查有效会话和用户目录审计，再显示 `Console login [PASS]`。从提示处按 `q` 可退出并保留已装服务，执行同一命令继续核对。

验收使用合成身份提供方、隔离存储和回环 HTTPS 代理。实际 Cloudflare 账号、DNS、R2 和身份提供方由上海部署现场核对。

新服务器的操作顺序见[一次命令完成首装](#一次命令完成首装)。签名发布已经具备打包和安装入口。上海服务器的可复制指令使用最终发布 URL、公钥摘要和启动器摘要。已有离线包也可以通过同一入口安装。

2026-11-25，`5412919` 的 Ubuntu amd64 隔离 CI 完成签名首装、真实 PostgreSQL 和 S3 兼容存储读写、OIDC 自动授权、重复安装复用账号、回环 HTTPS 反代及 App Link 精确文件回读。升级验收实际断开反代，确认原最终备份保留、写请求返回维护状态，恢复反代后用同一签名包续跑并解密原密文。另以历史二进制将 schema 4 迁移到 5，核对原密文及旧版拒绝打开新版数据库。发行版检测在本机覆盖 Ubuntu、Debian、Fedora、Rocky、CentOS、Alpine、Arch、openSUSE 和未知发行版的合成输入。完整容器实测为 Ubuntu amd64。正式发布源及其签名、上海服务器、实际 R2/OIDC 配置和实体双机仍待现场执行。[隔离验收](https://github.com/ForceMind/ZapIME/actions/runs/45587740283/job/139113571186) · [草稿 PR #128](https://github.com/ForceMind/ZapIME/pull/128)

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
sudo bash -c 'set -e; umask 077; stage=$(mktemp -d /tmp/zapime-launch.XXXXXXXX); trap "rm -rf -- \"$stage\"" EXIT; curl -fsSL --proto "=https" --proto-redir "=https" "$1" -o "$stage/bootstrap.sh"; printf "%s  %s\n" "$2" "$stage/bootstrap.sh" | sha256sum -c; bash "$stage/bootstrap.sh" --install --source "$3" --version "$4" --key-sha256 "$5"' zapime-install "$ZAPIME_BOOTSTRAP_URL" "$ZAPIME_BOOTSTRAP_SHA256" "$ZAPIME_RELEASE_URL" "$ZAPIME_VERSION" "$ZAPIME_RELEASE_KEY_SHA256"
```

已有离线包时，已核对摘要的启动器用 `--bundle /absolute/runtime`。入口参数后的 `-- --image-archive /absolute/images.tar` 传给运行安装器。服务器须已有可工作的 Docker 和基础依赖才能全程离线。安装器能在文件可用时按包管理器补齐基础依赖，使用 Linux、架构、Docker Engine 和 Compose v2 的实际能力决定是否继续。既有容器引擎优先复用。发行版仓库缺少所需 Docker 包时，输出缺少的运行时。

```bash
sudo bash /absolute/bootstrap.sh --install --bundle /absolute/runtime --version "$ZAPIME_VERSION" --key-sha256 "$ZAPIME_RELEASE_KEY_SHA256" -- --image-archive /absolute/images.tar
```

启动器验签前需要 Bash、curl、OpenSSL、Python 3 和 `sha256sum`。运行安装器会识别 apt、dnf、yum、apk、pacman 或 zypper，并按已有软件源处理依赖；带有可用 Docker Engine 和 Compose v2 的其他 Linux 发行版也可以直接运行。首次配置留在 SSH 终端，可按提示选择内置 Caddy、已有反代及 OIDC。部署后运行 `sudo zapime doctor` 核对结果。

首装向导收集域名、代理模式和私有 R2 参数。已有兼容 OIDC 时，可在提示中选择接入，也可在入口的 `--` 后传 `--oidc-config android.json --oidc-jwks jwks.json --oidc-subject-file subject.txt --android-signer SHA256`。subject 由身份服务的管理员界面核对，文件留在服务器。已有资料库加登录时显式传 `--oidc-account UUID`。新库在事务中建立唯一账号映射，重复运行复用持久选择。正式可用性还取决于签发者和客户端发出的实际访问令牌通过 ZapIME 的严格校验。

Docker 只读挂载公开 JWKS。Android 公共配置保存于 `/opt/zapime/oidc/android.json`，签名关联在 `/opt/zapime/oidc/assetlinks.json`。内置 Caddy 提供关联文件。已有反代时，将该公开文件通过管理员权限复制到代理可读的静态目录，仅将 `/.well-known/assetlinks.json` 映射到副本，其余业务流量送到 `127.0.0.1:8787`。APK 签名关联更新时重新复制并运行 `sudo zapime doctor`。安装结束分别报告存储、HTTPS、OIDC 和 App Link。

登录使用现有兼容的 OIDC 签发方。为公开 Android 客户端登记指定 HTTPS 回跳，启用授权码及 PKCE，并签发包含指定资源、客户端和 `zapime.device_enrollment` 作用域的 RS256 访问令牌。构建 APK 时用 `ZAPIME_ANDROID_OIDC_CONFIG` 指向复制出的 `android.json`。以 `apksigner verify --print-certs` 核对实际发给手机的 APK 证书 SHA-256 与公网关联一致，然后按[双机清单](ANDROID_ACCOUNT_SYNC_IMPLEMENTATION.md#实体双机验收)验证登录、首设备自动开通和第二设备恢复。

发布方用 `deploy/build_runtime_bundle.py` 打包。工具校验三个容器的实际平台及 ID，将源码提交、版本、架构、部署文件和镜像清单签名。在线镜像传仓库摘要引用。离线用 `docker save` 导出三项 `zapime-release/<服务>:<唯一版本>`，签名清单绑定归档 SHA-256 与实际镜像 ID。安装器在私有目录校验归档、核对已经存在的标签归属，再按固定 Compose 镜像运行。

#### 生成可传到上海服务器的离线包

在具备目标架构构建能力的 Docker 发布机上检出经过审核的提交。使用发布者保管的长期签名密钥。每个架构构建一次，`--architecture` 支持 `amd64` 和 `arm64`，输出目录按架构区分。

```bash
python3 deploy/prepare_runtime_release.py --version 0.1.0-server.1 --architecture amd64 --private-key /secure/zapime-release.pem --output ./out/0.1.0-server.1/amd64
```

这一步从同一 Git 提交取服务源码和安装文件，构建镜像及离线归档，写出 `runtime/`、`images.tar`、`bootstrap.sh` 和 `INSTALL.txt`。打包器打印提交、启动器摘要和发布公钥摘要。通过独立可信渠道把这两个摘要交给部署者。将成套目录传到上海服务器，在服务器进入该目录，核对 `INSTALL.txt` 中的两个摘要后执行其中的一行命令。升级继续使用同一长期签名密钥。

2026-11-25，本机用合成镜像清单实测了 Git 快照、OpenSSL 签名、归档绑定、重复输出目录拒绝及安装命令语法。`45e0c9a` 在 Ubuntu amd64 CI 使用同一打包入口生成真实镜像归档，回读一行命令中的启动器和公钥摘要，卸载发包镜像标签后用成套目录完成首装、重复安装、HTTPS 和升级回归。作业目录及测试签名只用于当次验收。供下载的双架构试装包仍需确认使用 CI 产物区；arm64、正式签名、上海服务器和实体双机尚待对应验收。[发包与安装 CI](https://github.com/ForceMind/ZapIME/actions/runs/45594644857/job/139137401162)

`24efde1` 在 Ubuntu amd64 CI 增加两项实测。用隔离回环 HTTPS 下载公钥、签名和运行 ZIP，并从签名绑定的本地镜像归档完成首装与重复安装。直接执行发包生成的原样 `INSTALL.txt` 命令，启动器篡改时在运行前被拒绝，原包完成历史 schema 4→5 升级且保留密文。此次 HTTPS 使用作业私有信任范围，线上发布地址和镜像仓库仍待验收。现有 CI 只保留测试日志。[一行命令及 HTTPS 下载 CI](https://github.com/ForceMind/ZapIME/actions/runs/45598541310/job/139150969400)

##### 可下载的双架构试装包

已交付 `0.1.0-server-preview.45611015601.1`。草稿 PR 源分支是 `f2ab55e`，两个原生 runner 实际构建的 GitHub 合并检验提交均为 `ea0560cd4bde625861b0643f8019cb5649d1d3c5`。两包均通过真实 PostgreSQL、S3 兼容存储、签名首装、重复安装、HTTPS 下载和 Android App Link 回读。原样一行安装命令在启动器篡改时拒绝执行，原包完成 schema 4→5 升级并保留原密文。同作业另用下一个测试版本通过冻结写入后的升级恢复。已从 Actions 下载两包，复核外层 ZIP 摘要、内层归档摘要、OpenSSL 签名、启动器摘要和三张容器镜像的架构。[构建与验收](https://github.com/ForceMind/ZapIME/actions/runs/45611015601)

| 服务器架构 | Actions 附件 | 内层 `.tar.gz` SHA-256 |
| --- | --- | --- |
| `x86_64` → amd64 | [下载 amd64](https://github.com/ForceMind/ZapIME/actions/runs/45611015601/artifacts/15020281796) | `74935c253224793f48645fa9ad33c57d12585c6a8f5930384c744ce8b19d5ae2` |
| `aarch64` → arm64 | [下载 arm64](https://github.com/ForceMind/ZapIME/actions/runs/45611015601/artifacts/15020537464) | `1bdc60f26283e0ed24eb3f722f505f210a423b8e5c892825e14add41121f1e8b` |

两个归档的启动器 SHA-256 均为 `dcf65311ba633e25c1c22c1372c68552214945a67439fa032bd1d2904cccd547`。amd64 临时公钥 SHA-256 为 `76d4143c190a33a3c100df934c1e6f9fc85d9f0bdc600e3725b493ce542e0d39`，arm64 为 `f2a86404884490319d7a7acc425f4639e49f9bf702717cae54236382dfd19935`。Actions 附件预计于 2026-12-02 到期。

将 ZIP 里的 `.tar.gz` 传到全新隔离 Linux 实例，核对上表的内层归档摘要，再解包并执行 `INSTALL.txt`。以下命令以 amd64 为例，arm64 替换归档文件名。

```bash
( mkdir zapime-preview && tar -xzf zapime-server-preview-amd64.tar.gz -C zapime-preview && cd zapime-preview && bash INSTALL.txt )
```

本次使用各架构独立的临时签名。正式安装使用干净实例及长期发布签名。后续构建试装包需显式选择现有工作流的 `server_preview` 参数。正式发布、上海服务配置和实体双机仍另行验收。

### 更新与中断续跑

`sudo zapime status` 显示已选版本、实际运行镜像、数据库 schema 及未完成的升级。 `sudo zapime doctor` 核对本机健康、公网实例见证及 Android 关联。`sudo zapime update <HTTPS发布目录或绝对包目录> <版本> [离线镜像归档]` 复用首次保存的签名信任。

升级先把数据库快照恢复到独立 Compose 项目，在复制库运行新版本迁移并校验不可变对象。之后冻结写入、停止旧进程、使用旧工具生成成套最终备份。候选服务在冻结状态核对对象、健康和此前已验证的公网路由，通过后切换版本并解除冻结。中断时按 `status` 显示的目标重跑同一签名版本，保留原最终备份。需要改回旧 schema 时，用对应版本及成套备份在空库执行恢复。已重新开放写入后，先保全新写入再选择恢复点。

客户端经过自有 HTTPS 服务处理账号、设备与 PostgreSQL 修订索引。端到端密文放在私有 R2 Standard 存储桶，只有自有服务访问对象存储。Android 账号登录、自动授权和资料同步按[安卓版验收清单](ANDROID_ACCOUNT_SYNC_IMPLEMENTATION.md)验收。

## 首次准备

- Linux amd64/arm64。签名运行包直接使用固定镜像。使用下文的兼容源码入口时，建议至少 2 GB 内存用于首次构建。安装器优先复用已有 Docker Engine 和 Compose v2。
- 一个已经拥有的域名，DNS 指向服务器，入站 80/443 可用。不会替用户购买域名或修改 DNS。若已有 Nginx/Apache 占用端口，使用下面的现有反代模式，不自动停止其他服务。
- 在 Cloudflare 控制台开通 R2，创建 **Standard、私有** 存储桶；保持公共访问关闭，不为 `zapime/v1/` 设置生命周期自动删除。
- 创建只作用于该桶的 **Object Read & Write** R2 凭据，记录 Account ID、Access Key ID、Secret Access Key，以及 bucket 所在 jurisdiction。不是普通 Cloudflare 全局 API Key。

Cloudflare 2026-10-22 官方定价：Standard 每月免费额度为 10 GB-month、100 万次 Class A 和 1,000 万次 Class B，R2 出站免费；超出额度或使用 Infrequent Access 会产生费用。该额度不是自动停费上限，且可能与账号内其它应用共用。请在 Cloudflare 查看用量与账单通知；本服务的每账号 256 MiB 保守配额不能保证整个 Cloudflare 账号始终免费。服务器中转流量仍受自己的服务器套餐计费。

来源：https://developers.cloudflare.com/r2/pricing/ 、 https://developers.cloudflare.com/r2/api/tokens/ 。

日常打字、搜索和使用已下载数据不访问 R2；无新记录的增量查询只查服务器数据库。HTTP 服务增加有界密文缓存，减少重复下载的 R2 读取；完整备份/校验仍直接读持久对象。具体功能、请求估算、备份成本及尚未接入的客户端调度见[同步用量](SYNC_EFFICIENCY.md)。

## 原有源码安装入口

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

## 用户中心与管理后台

2026-11-26 源码接线：本节适用于包含浏览器 Console 和 schema6 的新构建。上方 `2026-11-25` 试装包及其旧 schema4→5 证据保持原范围，不代表已包含或验收本节功能。本次未部署用户服务器，也未创建 Cloudflare Tunnel。

浏览器使用既有 OIDC 账号，身份服务必须给同一 `ZAPIME_OIDC_CLIENT_ID` 注册精确回调 `https://你的同步域名/console/auth/callback`，支持 Authorization Code + PKCE S256，并按原受众签发 `zapime.device_enrollment` 范围、至多五分钟的 RFC9068 RS256 访问令牌。服务继续使用管理员审核的公开 JWKS。管理员由精确 issuer/subject 名单授予；邮箱和客户端提交的角色不能授予权限。账号仍须有既有 OIDC 主体绑定，不会通过网页任意建立账号。

**先准备独立主密钥，再开启。** 在自有 Linux 服务器生成一次原始 32 字节文件；恢复已有托管数据时必须放回旧文件，不能运行生成命令。以下目录位于数据库 Docker volume 和 Web 静态目录之外，root 拥有，容器 GID `65532` 可读，不能被容器修改：

```sh
sudo install -d -o root -g 65532 -m 0750 /etc/zapime-console
sudo sh -c 'umask 077; set -C; openssl rand 32 > /etc/zapime-console/master.key'
sudo chown root:65532 /etc/zapime-console/master.key
sudo chmod 0640 /etc/zapime-console/master.key
```

命令拒绝覆盖已有文件。不要打印、粘贴、提交该文件，不放进发布包、镜像或公开 Web 目录。不要将它替换为 64 位十六进制文本、Base64 或带换行字符串。单独备份到受保护的离线位置，例如首次保存到一个全新文件：

```sh
sudo sh -c 'umask 077; set -C; cat /etc/zapime-console/master.key > /media/PRIVATE_RECOVERY/zapime-console-master.key'
```

`/media/PRIVATE_RECOVERY` 须是自己已准备的私密备份位置；保留已有备份，不覆盖历史。运行服务的机器和备份位置均能解开托管材料，应限制访问。`zapime backup` 不复制此主密钥。失去它不会被服务自动生成新密钥补救；仅凭数据库和 R2 管理凭据不能解开旧托管材料。持有原客户端密钥的设备仍可按原恢复流程处理自己的资料。

在 root 可读的 `/opt/zapime/config.env`（手工 Compose 则在私密 `.env`）增加以下配置，保留已有数据库、R2、OIDC 和安装标识。将示例域名和主体替换为已核实的实际值，origin 不带尾部 `/`：

```dotenv
ZAPIME_CONSOLE_ORIGIN=https://sync.example.org
ZAPIME_CONSOLE_AUTHORIZATION_ENDPOINT=https://login.example.org/authorize
ZAPIME_CONSOLE_TOKEN_ENDPOINT=https://login.example.org/token
ZAPIME_CONSOLE_SECRET_DIR=/etc/zapime-console
ZAPIME_CONSOLE_MASTER_KEY_FILE=/run/zapime-console/master.key
ZAPIME_CONSOLE_ADMINS='[{"issuer":"https://login.example.org","subject":"verified-admin-subject"}]'
```

OIDC `issuer`、端点和受众必须使用提供方的精确值，不能按示例猜测。机密客户端另设 `ZAPIME_CONSOLE_CLIENT_SECRET`；公共 PKCE 客户端留空。只有提供方的固定 HTTPS 重置适配器已实现时，才成对设置 `ZAPIME_CONSOLE_PASSWORD_RESET_ENDPOINT` 和 `ZAPIME_CONSOLE_PASSWORD_RESET_TOKEN`。适配器接受 JSON `issuer/subject/account_id/request_id`、服务端 Bearer 和同值 `Idempotency-Key`，只以 HTTP 200/202/204 表示受理通知；不得把密码或重置链接返回 ZapIME。未配置时界面禁用入口，超时或受理结果无法落审计时显示待核实，不自动重试，也不重置资料密钥。

在已安装本次新版本的服务器执行 `sudo zapime start`，Compose 会按新配置重建服务；手工部署使用 `docker compose --env-file .env up -d --wait sync`。启动只在启用 Console 时读取该绝对路径的普通文件，不跟随文件符号链接。读取后清理原始缓冲；缺失、尺寸错误或 Linux 上其他用户可读/组可写等过宽权限返回固定 `console_master_key_file_invalid`，不输出路径或内容。关闭 Console 时清空 `ZAPIME_CONSOLE_ORIGIN` 并重启，保留托管主密钥及备份。旧部署无需配置或创建主密钥。

浏览器打开 `https://你的同步域名/console/`，管理员使用 `/console/admin/`。页面通过同源安全 cookie、CSRF、当前账号校验和事务内复核访问资料；管理员读取其他账号正文先记录查看审计，审计失败不返回正文。封禁对现有浏览器会话和同步设备生效，解封不复活旧浏览器会话。本地设备已有副本仍可使用。只有明确接受管理员可读范围并托管原材料后才显示正文；旧账户未迁移时保留迁移提示。

**HTTPS 入口。** Caddy 模式已有 `reverse_proxy sync:8787`，会将 `/console/`、回调和 API 一起转发，无需把密钥目录映射到 Caddy。已有反代模式将同一公网 HTTPS 域名完整代理到 `127.0.0.1:8787`，保留请求 `Origin` 和 cookie，不缓存 Console 的 HTML/API/回调响应。不要将 Console 另挂到跨域地址或在 TLS 失败时退回公网 HTTP。

Cloudflare Tunnel 沿用 `--existing-proxy` 模式，由服务器上已授权、已配置的 Tunnel 把所选公开主机映射到宿主回环入口。已有配置的 ingress 形状如下；本项目不创建 Tunnel、域名或凭据：

```yaml
ingress:
  - hostname: sync.example.org
    service: http://127.0.0.1:8787
  - service: http_status:404
```

此例要求 `cloudflared` 运行在宿主机；独立容器里的 `127.0.0.1` 不指向宿主。浏览器与身份服务仍使用公开 `https://sync.example.org` 的精确回调和 origin，不能将回环地址注册为公网回调。Tunnel 自身的凭据不进入 Compose、数据库备份或源码。公网证书、实际 OIDC 登录和所选 Tunnel 路由须在获准的目标环境另验。

**升级与恢复。** 新版本仍使用现有签名安装/升级流程及 root 保护的 `config.env`；可选参数默认关闭，旧配置不要求额外密钥文件。升级试运行复制库时只验证数据库和对象，外部主密钥保留在原位置；最终启动才按上述路径加载，不能随发布版本重新生成。schema6 的备份包含受保护托管材料、封禁和审计，不允许旧服务降级打开。

在新服务器恢复时先放回**同一个**主密钥：目录仍为 `root:65532/0750`，文件为 `root:65532/0640`，设置相同容器路径与独立宿主源目录；然后按下节的空库成套恢复执行。没有旧主密钥时不得以新随机文件声称完成托管恢复。恢复脚本会使快照中的浏览器会话和待完成登录失效，要求重新登录；手机访问凭据及不可变资料历史按备份点保留，客户端恢复材料仍由用户另行保存。容器及真实 HTTPS/OIDC/恢复验收结果须分别报告，不能把配置单测或健康检查当作浏览器、生产部署或灾备完成。

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

原有源码入口重复运行时会保留配置、账号与数据库，先用当前版本备份，再构建并启动指定版本。源码安装文件保存于 `/opt/zapime/releases/<SHA>`。签名运行包使用上文的迁移演练与冻结流程。账号密钥身份、OIDC 主体映射及已开启托管的加密材料随完整 PostgreSQL 备份保存；外部 Console 主密钥单独保存。需要恢复旧版本时，从相应版本的备份恢复到全新库。

支持 systemd 时自动安装每日备份 timer。备份存放 `/opt/zapime/backups`，不自动删除旧归档；应监控磁盘并将成套备份复制到自己控制的异地位置。Docker 普通日志按 10 MB × 3 轮转，不记录正文/令牌。当前是可部署的开发服务，尚未完成第三方安全审计、外层限流与生产监控。

## 全新服务器 / 更换 R2 存储桶

`zapime backup` 产生一对私密文件：

1. `日期.dump`：一致的 PostgreSQL 账号、设备摘要、记录、操作与修订快照。
2. `日期.dump.objects.tar`：随后导出的不可变密文对象超集，采用标准 tar。两者一起保存；客户端另行保管 E2EE 恢复包/恢复码。已开启 Console 托管的部署另外独立保存同一外部主密钥，不在这两个文件内。

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
