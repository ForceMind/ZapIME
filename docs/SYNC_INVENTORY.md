# 本机同步证据的只读分页预览

这是 Issue [#77](https://github.com/ForceMind/ZapIME/issues/77) 的共享 Rust 基础切片。它让宿主以后可以区分“没有待发操作”和“存量记录已有同步证据”，**没有实现恢复记录的首次上传**。当前没有 JNI、页面按钮、HTTP、自动上传或后台任务。

records-only 归档恢复保留记录 ID、修订、来源与墓碑，但不会生成 `operations`。因此恢复后 `pending_count=0` 并不能证明全部资料已上传。此预览只描述本地数据库里的有限证据，不证明某条记录来自恢复、远端不存在、已完成云备份或可以安全重发旧墓碑。

## 调用与输出

宿主在独立同步/只读 worker 打开已有 SQLite v2 后，显式调用：

```rust
let page = zapime_sync::client::inventory_page(
    &mut store, profile, &material, after_record_id, 40,
)?;
```

共享客户端先验证 typed material 的账号，数据层在同一个只读事务里核对完整 `WorkspaceBinding` 的账号、key ID 和材料指纹，再读取该页。调用不创建或迁移数据库、不绑定材料，也不写记录、操作、队列、冻结请求、ACK、接收收据或 cursor。

每页最多 100 条，包含删除记录，以规范 UUID 的字符串顺序 keyset 分页。`next_after` 是本页最后一个 ID；只有存在后一条记录时才返回。它是本地浏览位置，不能当上传 checkpoint、远端 head 或用户批准。每页是独立一致读；跨页期间有插入或编辑时，前方新 ID 可能需要显式从头重查。没有全库 count 或“全部已同步”的推断。

输出仅含 ID、本地修订、kind、删除标记和下列证据字段，不含正文、标题、标签、来源设备 ID、密钥、密文或请求：

| 字段 | 精确定义 |
| --- | --- |
| `has_pending_local_operation` | 此 ID 存在 pending=1 的本机操作，可能只是较旧快照 |
| `current_snapshot_pending` | 存在 pending=1 的操作，其**完整存储 JSON 字节**与当前记录相同 |
| `has_local_operation_history` | 本机操作历史里出现过此 ID；不代表当前修订已有操作或已确认 |
| `current_snapshot_has_local_operation` | 当前完整存储 JSON 字节出现在本机操作历史中；不表示这个操作已确认 |
| `known_server_revision` | 本机 `sync_versions` 已知的正服务修订；允许 ACK 先于 pull cursor，不等于当前正文已被远端确认或远端完整备份 |

字节比较是保守证据；语义相同但 JSON 排列不同的历史不会被补认为当前快照。超过 1MiB 的历史操作不会进入当前快照字节比较，仍保留“有历史/有 pending”的证据，不能变为可自动上传的记录。

`state` 仅用于展示，按下列优先级选择；其他 flags 同时保留，不能被该优先级遮掉：

| state | 条件 |
| --- | --- |
| `pending_local_operation` | 有本机 pending 操作 |
| `server_version_known` | 没有 pending，但存在本机已知服务修订 |
| `local_operation_history` | 无 pending/已知服务修订，但有本机操作历史 |
| `untracked` | 本机没有上述证据，**不是上传授权或远端不存在的证明** |

## 资源与失败边界

记录正文首先在 SQL `CASE` 中限制为 1MiB 后才进行 JSON 标量检查；正文不会作为 Rust 字符串返回，包括一条 lookahead。规范 ID、存储修订与正文修订一致、布尔删除标记及 kind 都必须通过。未知 pending 状态、缺少/不规范的操作记录 ID 均拒绝整页，不能靠只查 pending=0/1 把坏历史忽略为 untracked。

查询复用 v2 既有 `sync_pending_record` 索引，不在普通输入会话追加新的大索引构建。已有库若缺少该索引会明确失败，预览不会迁移。ID keyset 与 pending lookup 可用索引查找，其他历史检查可能扫描既有覆盖索引；**每页输出有界不意味着总查询工作只与页大小有关**。大历史可能触发已有 200ms SQLite VM 协作预算。该预算不包含不可中断的磁盘/操作系统调用，不是手机延迟或硬墙钟保证。

返回错误没有部分成功、自动重试或持久页游标。所有原记录、冻结字节、ACK 与 cursor 原样保留；下一次调用仍要重新核对当前完整材料身份。

## 已验证与未完成

真实 SQLite 回归覆盖 records-only 恢复且 pending=0、307 条含墓碑资料按 7 条分页、无正文输出、全表逻辑与 schema 不变、错误完整绑定/非法限制/坏元数据/缺索引拒绝，以及实际 SQLite VM 中断后的清理与重试。共享客户端测试使用真实 AEAD 两库交换，验证正常下载及同内容 adopt 保持零回声、ACK 可以领先 pull cursor、旧本机操作不能冒充远端更新后的当前快照，冻结请求字节保持不变。

这不是 Android 页面、JNI、真实服务 HTTP、手机或跨设备验收。首次发布仍需要用户确认、先拉取远端、已存在版本/唯一性/墓碑冲突处理、有界幂等新操作及可恢复进度，按 Issue #77 继续独立实现；不要从 `untracked` 自动生成操作，也不要修改旧冻结请求或把下载内容重新上传。
