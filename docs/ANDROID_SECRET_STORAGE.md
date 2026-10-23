# Android 同步材料的本机保护（候选）

`SyncSecrets` 为未来专用同步worker提供有界不透明材料的保存/读取/显式忘记接口。目前没有生产调用方、账号设置或HTTP同步；不能据此宣称已完成客户端登录、E2EE密钥托管接入或跨设备同步。

AndroidKeyStore生成256位AES保护密钥，GCM使用系统生成的随机IV、128位认证标签与版本/账号UUID绑定的AAD。材料最多64KiB；保护密钥不导出，实际硬件支持取决于设备，不强制StrongBox或每次生物识别。E2EE记录仍由共享Rust密码模块处理，系统密钥只封装本机同步材料。

密文放在应用私有noBackup目录，版本化二进制封装与AtomicFile更新。拒绝符号链接/非普通文件，读写有固定大小上限。保存结束会重新解密核对已提交内容，避免框架finishWrite仅记录I/O错误却被调用方当作保存成功；这不是断电耐久性承诺。所有入口先检查非主线程，再进入互斥区，主线程误调用不会等待后台文件/Keystore操作的锁；也不得从输入worker调用。

不存在文件返回无配置；既有密文缺少保护密钥、标签/账号绑定失败或格式损坏均明确报错，不能退回明文，也不生成新密钥覆盖旧密文。只有未来显式恢复/重置流程才能调用forget。调用方拥有输入/输出字节数组并须及时清零，不把内容、令牌、恢复码或密钥写入日志/SharedPreferences/开放JSON。

保护密钥属于这台设备。更换设备、卸载、密钥失效或恢复仅密文文件时仍需要独立用户恢复材料；本模块不生成E2EE根密钥、不导出恢复包、不传输根密钥或令牌。未来账号/配对、Rust密钥接口、用户恢复UI和其它平台受保护存储需要接续。本地SQLite业务正文与原有开放归档仍为明文，不能把这个辅助文件的加密称作全库静态加密。

## 验证

signed-target测试只使用两个随机测试账号及合成字节，最后清除自己的文件/别名。新增真实AndroidKeyStore往返、重复保存nonce变化、保护密钥不可编码导出、标签/账号交换拒绝、模拟未完成AtomicFile更新后旧值恢复、后台持锁时主线程快速拒绝、密钥删除后读/写拒绝静默替换。模拟未完成文件不等于真实断电或杀进程；软件模拟器不证明硬件密钥实现。

本机JDK17.0.21/API36r02产品与两个测试源集编译、11项Python资源/设备隐私边界通过。实际三API Keystore测试尚待CI，不预记通过。

官方接口依据：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)、[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile)。
