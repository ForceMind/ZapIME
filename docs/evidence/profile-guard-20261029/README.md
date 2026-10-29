# 持久资料库账号保护 · 候选验证

本地Rust工作区80项、fmt/严格Clippy及Java产品31源/55类、探针5源/12类编译通过。新增Rust用例使用完整Store及个人记录/待发历史，验证只读检查不创建主库、不改变业务/队列和绑定，拒绝损坏/非规范账号及未来schema。local-rust-selected.log为原日志逐行摘录，local-rust-summary.json与local-java-compile.json记录对应源码SHA；不是Android JNI执行。

新增Android五类探针使用自己的SQLite v2元数据夹具，通过真实JNI与实际连接vault测试；不会绑定或迁移用户资料库。新源码APK/三API结果待CI，不能借用前一版本通过结果。账号快照不替代事务内stage/apply校验，未启用上传/拉取。
