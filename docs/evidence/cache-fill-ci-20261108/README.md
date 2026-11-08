# 有界缓存的真实 PostgreSQL / HTTP / S3 / 灾备验收

[workflow42460687991](https://github.com/ForceMind/ZapIME/actions/runs/42460687991) / job128669118685 完整 SUCCESS。head `f01a8b5803ebcc5111b1d6ffa7271c9db61a8a63` 与实际 checkout `0d5edd686e9f23c639099e71004888bf568c88a1` 的 tree 同为 `6350807ec26343dcd395d532364294efc24badc0`；checkout父和tree均通过GitHub原始Git API核实。

原 artifact13609793792 已实际完整下载，1,798字节、SHA256 `37bfcf92ff0e5745a22a8a17f8d79327ea21ecdfc12226b2431ee7ab65f8b050` 与GitHub一致，ZIP及唯一原testlog保留原字节。完整安全job日志保真gzip、原响应摘要与逐文件摘要见manifest。

实际 Go1.27.3 / race 顶层30组（29项测试加1组fuzz seed）、0 SKIP。新增 `TestPostgresObjectCacheHTTPAdmissionOutlivesCancelledHandlers` PASS：真实事务和HTTP取消释放入口槽，但原后端fill仍持容量；新key整页503无部分内容，后端实际退出后同cursor重试返回正确修订/游标。原撤销、隔离、冻结请求与key identity门禁均保留。

同一实际作业的真实S3协议/条件写/完整归档、容器构建、Go漏洞门禁、Rust共享客户端密文交换和空服务器恢复、一条命令/重复安装/新数据库与替代对象桶恢复全部SUCCESS。模型、用户设备、真实Cloudflare凭据、公网HTTPS及生产服务器未参与本轮；S3兼容夹具不冒充实际R2账号。

原无限后台fill复现和本机race反例仍在[前序证据](../cache-fill-bound-20261107/README.md)。本次结果对应固定源码；之后仅合并已独立验证的发布脚本和文档，不为新main伪造又跑过一次事务的记录。Android原生剩余门禁及历史组字失败继续分别记录。
