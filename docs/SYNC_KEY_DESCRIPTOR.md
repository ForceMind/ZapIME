# 服务端账号密钥身份

这是一项服务端安全前置能力，防止多个正常owner设备把不同根密钥当作同账号的第一套密钥而直接上传。它没有实现设备配对、秘密传输、历史迁移、密钥轮换或完整客户端同步。

## 固定接口

`PUT /v1/key-descriptor`只接受：

```json
{"version":1,"key_id":"规范非零UUID","material_sha256":"64字符小写SHA256"}
```

摘要来自本机材料v1的固定72字节：`ZSKM`、四字节版本尾`00 00 00 01`、账号UUID原始16字节、key UUID原始16字节、随机根密钥32字节。不能发送原材料、根密钥或恢复码。根密钥由密码学随机源生成；公开摘要只是身份元数据，不提供根持有证明，不能当登录令牌或配对码。

GET和PUT成功都返回固定六字段：

```json
{
  "protocol": 1,
  "account_id": "当前令牌所属账号",
  "state": "registered",
  "descriptor": {"version":1,"key_id":"首次key UUID","material_sha256":"首次摘要"},
  "registered_by": "首次owner设备UUID",
  "registered_at": "RFC3339时间"
}
```

未登记时state为`empty`或`legacy`，descriptor、registered_by、registered_at三个字段明确为null。GET不接受正文，两种请求都拒绝查询参数；PUT最多1024字节并严格拒绝重复/未知字段、类型错误和尾随JSON。

- owner首次登记要求head=0、stored_bytes=0、records/operations均不存在。
- member可以读取核对，不能登记。相同owner描述符重试保持最初设备/时间；不同值409 `key_descriptor_conflict`。
- 有历史却未登记时409 `key_descriptor_legacy`，不猜测密钥、不清空历史。
- 已登记账号的新Push若key ID不同，409 `key_descriptor_mismatch`，不写R2、不增加修订/配额。旧不可变重放仍按原请求先检查；未登记legacy账号原API行为保持兼容。

所有步骤复用账号行锁和事务内授权复核。预认证后被撤销、到期、角色变化或令牌轮换的actor不能继续登记/读取。接口不返回令牌摘要或根秘密，响应no-store，不读取R2，也不改变客户端cursor。

## 客户端必须保留的边界

客户端须对照本机账号、key ID和材料摘要。冲突不授权自动换根、重加密冻结操作或删除本地资料。竞争失败的设备应保留材料/资料，随后执行明确迁移；当前不是原地一键恢复流程。已有的本机材料准备并不自动触发登记，当前Android尚须单独接入该HTTP流程。

服务器CAS依赖诚实、持久的数据库状态，不能替代双设备核对或抵抗恶意管理员回滚。服务器丢失历史、只恢复旧备份或删除描述符后，不应让客户端据此认定可以重置账号映射。完整恢复仍需要原密钥材料、资料与一致的服务器备份。

## 各版本及灾备

| 项目 | 版本 |
| --- | --- |
| HTTP changes/session协议 | 1，保持兼容 |
| key descriptor | 1 |
| PostgreSQL schema | 3 |
| R2对象引用storage_version | 2，保持不变 |
| 本机SQLite user_version | 2，保持不变 |

迁移从服务器schema1/2到3，不为旧账号猜测或生成描述符。旧v1/v2服务二进制拒绝打开schema3。升级前完整备份，不能切旧镜像当作数据库回滚；需要时恢复到全新数据库。

新的`zapime_account_keys`完全包含在原完整pg_dump中，没有额外根密钥侧文件。R2模式依旧同时保存`.dump`和`.dump.objects.tar`；恢复后必须核对原描述符、设备撤销、修订和密文，并由客户端重新认证解密。

## 验证状态

本地固定Go1.27.3和PostgreSQL16.11已在云工作区隔离随机端口运行：真实PG的CAS竞争、同根幂等、跨账号、成员权限、预认证后撤销/轮换/到期、legacy保护、注册与旧Push竞态、对象零调用、schema1/2迁移与未来版本拒绝均通过，`go test -race -count=1 -v ./...`和`go vet ./...`成功。纯解析器覆盖1024字节边界、重复转义键、未知字段及尾随输入。

容器S3/安装器恢复脚本已新增同一真实AEAD根的描述符登记与恢复断言；本地没有Docker，不能把脚本修改视为这些门禁已通过。它们等待对应提交的隔离CI。官方固定工具包只放在ignored target，未安装系统服务、未访问用户服务器或Cloudflare账号。
