# 持久资料库账号保护 · 2026-10-29

最终源码 `7466514b16ede5978a169efcd1b32aceba124021` 经 [PR #66](https://github.com/ForceMind/ZapIME/pull/66) 合入 `714101399342f926e8a41f4fd3020df1ab9164e2`，跟踪 [#65](https://github.com/ForceMind/ZapIME/issues/65)。CI临时合并 `414824972026774577ac9108538671b4f4fd33a7` 与两者 tree 均为 `b924af1448d2e0f7eed5e72f89cda4c0fdfa0183`。初始4307不是最终验收对象；补强主线程检查必须匹配明确拒绝码后，只引用本最终head。

## 实际结果

[Android 40845624970](https://github.com/ForceMind/ZapIME/actions/runs/40845624970) 双架构构建及三个模拟器作业全部 success。每API五类新增检查：缺少主库不创建目录或主库、规范绑定、跨账号及移除连接后仍拒绝且原vault不变、损坏/未来schema失败、明确主线程拒绝。探针使用自己的SQLite v2元数据夹具及实际连接vault，不触碰用户资料；完整Store/个人记录/待发历史保留另由Rust测试覆盖。

| API | 新资料库保护 | 原连接 | 原空PCM | 原语音事务/Editable | 产品UID | IPv4/IPv6拒绝并清理 |
| --- | --- | --- | --- | --- | --- | --- |
| 28 | 5/5 | 7/7 | 6/6 | 20/20 | 10067 | 通过 |
| 35 | 5/5 | 7/7 | 6/6 | 20/20 | 10209 | 通过 |
| 36 | 5/5 | 7/7 | 6/6 | 20/20 | 10216 | 通过 |

原两轮真实系统输入、JNI合成音、录音/权限、空结果保护和服务退出门禁保留。三个combined-gates两个退出码均0，offline-uid双族拒绝及removed均true。原始报告按API保留，未改写字段或正文。

[通用40845624881](https://github.com/ForceMind/ZapIME/actions/runs/40845624881) 三系统Rust各80项/fmt/严格Clippy、Apple构建及3个iOS测试通过。新增两项完整SQLite测试在三个系统实际PASS；对应selected.log为原作业日志逐行摘录，Windows的CRLF保留。[服务40845624905](https://github.com/ForceMind/ZapIME/actions/runs/40845624905) 强制PG/S3、身份/撤销、Docker、漏洞、真实Rust密文、新服务器及一键安装重跑/另一空桶恢复均通过。[Windows40845624942](https://github.com/ForceMind/ZapIME/actions/runs/40845624942) 和[离线CPU40845624912](https://github.com/ForceMind/ZapIME/actions/runs/40845624912) success；主机回环TLS31项报告也从API28 ZIP原样保留，不代表Android正向HTTPS。完整五工作流metadata见workflows.json。日志摘录不是完整日志，原件仍在Actions。

## 文件核验

artifacts.json记录已实际下载并完整size/SHA核对的三个ZIP。api-28/、api-35/、api-36/为ZIP中相关JSON、instrumentation、生命周期、退出/运行时等文本原件；大体积系统input-method-state、截图、音频、模型和APK留在原始ZIP。manifest.json记录本目录每文件的SHA/大小/来源，不含清单自身。原件的末尾空行保留。

package-verification.json记录本地实际检查双架构APK/DEX、JNI动态导出、固定模型六成员、原生依赖hash和许可资产；未把测试探针、合成PCM或模型权重打入产品。签名、manifest禁止明文和16KiB对齐由CI检查；未冒称本地签名或ARM运行。固定下载见[ANDROID_TRYOUT](../../ANDROID_TRYOUT.md)。

local-java-compile.json记录产品31源/55类与探针5源/12类的实际Java编译和源SHA；local-rust-summary.json及逐行摘录记录本地Rust80项/fmt/严格Clippy。临时目录被环境清理前已提交这些记录；恢复最终源码后核对源SHA，不把重新克隆当作重新运行测试。这些本地检查不是Android JNI执行，后者由本源上述三API报告单独证明。

## 边界

只读状态不创建主库、不迁移、不修改绑定、个人记录或队列，也不分配输入会话；保存连接时的快照不能替代未来每次stage/apply的事务内require_profile。缺绑定不会自动绑定。未接E2EE创建/恢复、跨账号迁移、上传/拉取或四端互通；没有Android正向HTTPS、用户服务器/真实Cloudflare或真机验收。语音合成检查不是自然语音或物理非空确认；开发签名不是正式发行。
