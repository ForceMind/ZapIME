# PR74服务端：真实隔离CI与产物验证

固定源码`e8e016cb6fe932d58cad2b04ed5ed6ab0f89ed9e`，run42350448294，job128337307776，全部步骤success。原始job日志、GitHub元数据和独立验证保留原bytes。23个顶层Go Test和Fuzz种子组通过、0 SKIP；真实PG16/S3兼容fixture、容器、govulncheck、旧Rust crypto probe、双设备密文/新数据库灾备、一键安装、重复安装和全新DB+对象桶恢复门禁通过。

实际下载artifact13565866875的1613-byte ZIP，SHA256与GitHub digest一致；验证唯一成员sync-service-tests.log的CRC、长度和SHA256并保存原始成员。ZIP留在忽略的临时目录，不进入仓库。详细值见artifact-verification.json。

该次CI运行的是旧crypto fixture及本切片服务端，不包含下一轮新共享manual client。它也不是实际Cloudflare R2、公网HTTPS、Android同步或真机证据。
