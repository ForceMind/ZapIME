# Android 本地同步状态预览：本机验证

本切片把既有只读共享 inventory 接到 Android JNI、严格 JSON 解析和显式分页 Activity。产品提交 `18a2066`；后续与 main `88112f4` 的合并不改变产品树。精确源树、逐文件 SHA、命令与原日志 SHA 见 [validation.json](validation.json)。测试范围互有包含，不把各组数字相加当独立场景。

本机根 workspace 144项、fmt/严格 Clippy、51项同步 Python、10项打包检查通过。62个产品/4个独立宿主/20个语音探针 Java 源以核验的 JDK17/API36r02 编译。实际 `android_sync.rs` 原字节另在临时 host JNI crate 编译并运行3个既有 helper 测试；这不是 Android 目标链接或 JNI 实际调用。

新增三 API 离线同步门禁复用既有受保护 CI：transfer 报告 schema2要求实际只读 JNI、真实 records-only 导入43条/两页/6墓碑、身份与严格解析拒绝及完整持久状态不变；pages 报告 schema2要求实际入口/40+4分页、当前页替换、排队取消、离开清页和迟到拒绝。旧 schema1 不能代填。页面夹具是独立合成行，真实恢复路径另由 transfer JNI 门禁证明。

**本机未执行 Android JNI/Activity/手机验收。** 必须以新固定树读取真实 CI，不能用本机编译、旧组字 CI 或旧同步 UI 成功代填。本切片不增加 HTTP/首次上传/后台同步；Issue #77 保持未完成。
