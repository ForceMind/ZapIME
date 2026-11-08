# 本机同步证据预览：PR #100 固定源码 CI

PR [#100](https://github.com/ForceMind/ZapIME/pull/100) 的head为`4c98059dc95c56bd46cf568ec9189b1bdbfae591`，实际PR checkout为`c50e375966c302598dcc23dc2cc032e2ae07cb5c`。Git API回读两对象均为tree`71972650824144dcb3c59952e541ff2ea26f6b9c`，与本机a6ce6de的源码树一致。本目录仅记录attempt1，没有手动重跑。

| 作业 | 固定结果 |
| --- | --- |
| Source42515690560 / prepare128838076128 | SUCCESS；原日志实际30项发布回归、143项根Rust、0 failed/ignored、fmt/严格Clippy、精确checkout源码prepare通过。publish按PR条件SKIPPED，没有新公开版本。 |
| Shared Windows128838057774 | SUCCESS；140项、0 failed/ignored，新9项逐名出现 |
| Shared macOS128838058051 | SUCCESS；143项、0 failed/ignored，新9项逐名出现 |
| Shared Ubuntu128838070043 | SUCCESS；143项、0 failed/ignored，新9项逐名出现 |

Windows比Unix少的3项为既有Unix条件测试未编译，不是ignored。四份日志各有27个Rust结果组（包括0项doc-test组）；这些是同组测试在不同job的执行，不能相加当独立样本。新9项为records-only恢复零pending基线、EXPLAIN实际索引计划、6项data inventory与1项真实本机AEAD client回归。

`*.log.gz`保存工具返回完整decoded日志的UTF8字节表示，gzip mtime=0，各`*.log.json`给解压/压缩长度和SHA。BOM和Windows CRLF均保留；没有把工具文本称为原始服务端传输ZIP。`validation.json`由四原日志逐行重算测试数、逐名核新9项、确认fmt/Clippy命令及完整checkout产生；`source-identity.json`和三个jobs元数据保留固定范围。没有下载source artifact或将其称为独立下载核验。

捕获jobs快照时，Apple native仍在进行、服务作业仍在初始化容器。因此没有把整个shared workflow或Go/PostgreSQL事务检查报为通过；没有数据库SKIP可被替代为通过。本地只读预览没有宿主JNI/UI/HTTP接缝，这些共享SQLite/AEAD与编译证据不构成Android、iOS、macOS系统激活、手机、服务器互通或恢复记录首次上传验收。

Issue77继续open，预览不会创建operations、重写冻结请求、移动cursor或发送HTTP；untracked与known-server只表示本机证据，不是上传授权或完整云备份证明。根源Alpha标签和附件未修改，未操作个人设备/生产。全部测试资料为自写合成数据。

## 服务作业的后续最终回读

run42515700598/job128838135809现已最终SUCCESS，attempt1且同head4c98059/checkoutc50/tree719。原先pending元数据保持不改，新增`jobs-42515700598-final.json`和`service-final-validation.json`记录后续结果。完整170316字节decoded日志已保真gzip；实际下载artifact13631598038（1790字节），核GitHub SHA a9932faf…cab9f9，唯一成员`sync-service-tests.log`的8982字节原文/SHA也保存。

实际Go1.27.3在强制PG16与S3条件下执行race：29个顶层测试组及1个fuzz-seed组，0SKIP；真实事务/HTTP/撤销、S3协议/归档均通过。容器构建/漏洞门禁、共享手动client 17命令/2库/2修订/两端0pending、双端交换/撤销/空服务器恢复、安装/重复安装/空库及替代对象桶灾备步骤均有原日志与SUCCESS步骤佐证。不是仅凭作业绿色代填事务通过，也不把fuzz种子执行说成长时间随机fuzz。

这些是既有共享同步与服务回归，不能证明新增预览已有HTTP/JNI/UI接缝、Issue77完整首次发布、手机、生产或真实Cloudflare凭据验收。没有重跑；原Alpha标签/附件未动。
