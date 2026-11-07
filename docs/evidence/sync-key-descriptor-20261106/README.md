# 服务端只创建一次的密钥身份：本地真实验证

源码是基于固定5b2d9488的独立服务切片，`verification.json`逐文件记录大小/SHA。没有根密钥、恢复码、私密fixture、凭据、PG数据目录、备份、二进制或下载签名URL进入本目录。

- 官方固定Go1.27.3与自编PostgreSQL16.11仅在云工作区target下使用，不安装系统服务。PG只监听随机loopback TCP端口，测试后停止。
- `go test -race -count=1 -v ./...`：21个顶层Test通过，Fuzz种子组通过；两个依赖真实S3后端的测试明确SKIP。本地无Docker，不能把SKIP称作对象/容器/安装器验收。
- `go vet ./...`成功。旧重放/密文/撤销基线保持，新CAS竞争、同根幂等、root身份冲突、跨账号、成员权限、到期/撤销/轮换、legacy保护、Push竞态、零对象调用和schema迁移均实际执行。
- 真实新服务二进制与Rust fixture：生成真实随机root并认证其account，独立72字节v1 framing摘要；另root登记和写入被拒；原AEAD记录成功、设备撤销；实际pg_dump→全新DB pg_restore后描述符的设备/时间/摘要完全保留，恢复root再次认证解密。私密fixture和临时备份已删除。
- 实际从af40cd6构建原v2服务二进制，尝试打开schema3返回database_migration_failed，数据库没有降级。不是复制旧判断分支的模拟测试。
- 初轮unused import编译失败、PG进程跨工具执行结束而消失的失败、Rust sha2 Array LowerHex失败均保留。前两项工具/夹具已修复，LowerHex改成逐字节hex后check/build/fmt通过。当时在主工作树运行strictClippy曾被并行数据层未提交type_complexity阻塞，该失败原样保留。随后从固定5b2d9488用独立index导出仅本切片源码的快照，workspace all-targets strictClippy、workspace fmt、locked crypto example check/build全部通过，见descriptor-isolated-*.log。真实执行seal→descriptor两次→verify；同root身份保持，错误account在输出描述符前失败，私密临时文件已删除。

容器S3及一键安装/新桶恢复脚本已补同一真实root描述符的登记与恢复断言，等待对应提交CI。这里的本地inline数据库演练不替代这些项目，也不代表真实R2、公网HTTPS、Android同步或手机验收。

独立快照的Cargo.lock相对基底只增加zapime-crypto→现有sha2依赖边；没有任何依赖版本、来源或校验和漂移。快照不包含并行Android/数据层工作树改动，生成与检查不修改主index、checkout或HEAD。`verification.json`记录固定基底、检查前树与全部保存日志的摘要；最终源码/证据由本目录所在提交定位。
