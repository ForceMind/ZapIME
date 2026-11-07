# ADR-023 · 一次性 API28 模拟器的真实 HTTPS 同步验收

日期：2026-11-06。状态：测试切片实施中，实际执行结果必须另行记录；本ADR不是通过证明。

## 目的与证据层级

已有主机回环TLS、真实Go/PostgreSQL服务交换和Android离线JNI测试分别通过，仍不能证明Android生产传输协调器实际经过平台TLS访问真实服务。新增独立API28作业，使用原产品APK、平台默认信任和主机名校验、原Go服务与全新PostgreSQL，执行真实授权、密钥登记、双向密文传输和认证落库。

仅为这条测试，在一次性API28的临时系统副本加入本轮临时CA。产品不携带测试CA，不修改network security config，不设置自定义TrustManager、SSLSocketFactory或HostnameVerifier；服务业务由真实后端处理，测试TLS代理不能返回伪造的业务、ACK或密文页。旧JVM与离线测试报告的原scope保持不变。

成功最多证明：一个API28模拟器、两个隔离资料profile、临时系统信任库、受控TLS终结与原Go/PostgreSQL的实际加密交换。不能推导原厂信任库、公网证书、两台手机、三星S25、Cloudflare R2、配对或四端完成。第二个profile不是另一台设备，也不证明独立设备Keystore间迁移。

## 执行保护

只允许专门的GitHub Actions作业调用 `scripts/android_sync_https_smoke.py`。必须同时满足：数字run/attempt、指定emulator序列、qemu、API28、userdebug/eng、root UID0和SELinux Enforcing。AVD强制新建、writable-system、wipe-data、no-snapshot，不能缓存此AVD或系统副本。工作站、手机、已有用户环境及生产服务器不属于允许范围。

CA由本轮host临时目录生成，使用subject_hash_old定位且目标原本不存在；已有证书一律不覆盖。仅添加自己的PEM，检查SHA256、root所有权、0644及正常SELinux标签。不关闭SELinux，不disable-verity，不使用隐藏根库开关或修改Conscrypt APEX。API35/36根库独立，当前作业不宣称覆盖。

产品精确UID只允许连接10.0.2.2上的本轮两个TLS端口，拒绝其余IPv4和全部IPv6。只创建/删除自己的规则，不flush、不接管其它规则。测试前后保留系统根证书的公开文件名/哈希基线。

## 三阶段强门禁

1. before：新产品进程用生产传输代码访问本轮未受信CA，必须明确TLS拒绝，网络错误不算通过。
2. roundtrip：添加CA后启动新产品进程，正常验证真实owner/member授权，创建并本地恢复同根材料、登记/核对描述符，通过生产SyncTransfer完成A/B真实上传、ACK、拉取和解密。两个profile最终游标2、无待发回声；同CA错误SAN端点必须TLS拒绝，代理不能收到它的应用HTTP请求。
3. after：只有证书仍与本轮指纹一致时才删除；新产品进程须再次TLS拒绝，系统证书基线完全恢复，自有临时文件和网络规则清理完成。

只重启本轮新安装的产品/测试App进程，不把force-stop本身称为TLS缓存已清除；正向和删除后的实际请求负责证明观察到的信任结果。任何阶段、清理或基线验证失败都不能整体PASS；保留失败阶段与原报告，不以清理成功替代原操作成功。

## 秘密和失败处理

root材料、恢复码、访问令牌、正文、密文体和CA私钥不进入命令行、普通日志或公开artifact。动态合成设备凭据只经测试侧私密bootstrap HTTPS交给test-only宿主，随后必须走生产身份验证；根材料与恢复码只在两个隔离本地profile间通过既有恢复接口使用，绝不发送给代理或后端。

host私密临时目录与公开证据目录分开。公开报告只有固定case、计数、公开证书指纹/端口、API、scope及清理证明；禁止通用UI/无障碍文字转储和私密目录通配上传。出错时输出固定阶段/错误类型，不能输出任意异常正文或服务器错误body。临时Go进程、TLS线程和本轮文件必须回收，不删除或清空外部数据库。

本工作区无KVM/Android模拟器；主机编译和脚本单测不能填充这三阶段结果。实际CI通过前保持NOT RUN/BLOCKED。
