# 架构与当前实现

## 已实现的输入链路

Android InputMethodService → 单线程后台队列 → JNI → zapime-core → MSIME Session → Rust 拼音引擎。UI 主线程只负责按键收集、候选显示与 InputConnection 上屏；首次词库安装与会话准备在后台串行执行。每个输入框有 epoch，失焦后的旧结果不提交到新输入框。每个候选快照有 generation，旧候选点击不提交。

词库随包携带、构建时验证 SHA-256。应用没有 INTERNET 权限。资源、个人数据和缓存是三个独立目录，个人词库的工作副本由上游 generation 机制准备。当前候选列表最多显示前 40 条，完整分页仍待迁移。

## 上游与扩展边界

vendor/msime 为精确锁定的完整源码，内含 engine、input-runtime、host-api 与四端原生宿主。当前链接 engine 和 path-trust，尚未迁移完整 host-api 服务图。根目录 crates 为 ZapIME 自有适配、SQLite 个人数据与认证加密/恢复模块。Android 原生管理页通过同一后台 JNI 会话读取、编辑与导入导出个人资料；输入会话缓存有效规则并按精确编码应用。

## 后续链路

Android 键盘内的本机资料面板通过独立 query 请求读取最新记录，插入时复核 ID、修订和过期时间；搜索用第二个不学习的输入会话，不把查询词提交到宿主正文。所有句柄由原串行 worker 管理，epoch 与面板实例过滤迟到结果。此链路只用本地数据，详见 [LOCAL_DATA_PANEL.md](LOCAL_DATA_PANEL.md)。

共享本地数据（SQLite、版本化归档、离线操作）→ 后台同步客户端（E2EE）→ 独立 Go/PostgreSQL 服务。客户端保存可迁移副本与密钥恢复资料。服务端不可接触明文正文。永久收藏与临时剪贴板按类型分域、采用不同生命周期。当前 SQLite 同表存储有类型记录，未声称物理分表。详见开发计划；此链路尚未完成。

## 其余平台

已新增 ZapIME 自己的最小原生宿主，均调用同一个版本化 C ABI，并与上游身份/资料隔离：

| 平台 | 原生宿主 | 输入执行与边界 |
| --- | --- | --- |
| iOS | 主 App + UIInputViewController 扩展 | 专用 Thread 驱动 Rust；主线程操作文本代理；不请求完全访问 |
| macOS | InputMethodKit App | IMK 本地同步调用 Rust；候选非激活浮窗；ad-hoc 开发签名 |
| Windows | TSF COM DLL | TSF 同步 edit session 取得读写锁后调用 Rust；非激活候选浮窗 |

Apple/Windows 当前默认不学习、不联网，未接入个人资料管理；编译/原生桥接已通过相应 CI，但系统输入源启用、真实 App 输入、签名和平台兼容仍待验收。最小宿主不等于上游完整功能，不能把 submodule 的品牌安装包视为 ZapIME 产品。


## 独立密文服务（客户端尚未接入）

`services/sync` 使用 Go/PostgreSQL 独立实现。服务访问凭据与 E2EE 根密钥分离；管理员 CLI 建立账号/首设备，owner 授权其他设备。每条写入持有账号行锁，事务内复核摘要令牌/撤销/到期，执行幂等检查、实体 CAS、不可变变更日志和账号单调游标递增。

服务只保存与 Rust `zapime-crypto` v1 相同格式的密文及最小元数据。客户端负责 AEAD 验证和正文/外层元数据核对。数据库备份可恢复账号和历史游标；用户自持恢复资料才能重新得到根密钥，二者不能互相替代。

实际 PostgreSQL 与 Docker 合成测试已验证两设备密文交换、撤销、备份、空数据库恢复和新客户端进程解密。共享库已增加持久上传准备层：显式待发序列、账号绑定、首次密文请求原样重试、服务端 revision 映射和事务确认；确认不覆盖后续本地编辑、不推进拉取游标。此层尚未联网。远端认证应用、冲突副本、游标原子推进、核对码配对、Keychain/Keystore/DPAPI 及四端用户界面仍未接通。详情见 [SYNC_STATE.md](SYNC_STATE.md)、[SYNC_PROTOCOL.md](SYNC_PROTOCOL.md) 和 [服务手册](../services/sync/README.md)。
