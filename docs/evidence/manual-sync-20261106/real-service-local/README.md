# 真实共享手动同步客户端：本地服务验收

本目录逐字节复制云工作区实际执行的私有夹具验收日志与原始verification；没有根密钥、账号token、恢复码、SQLite或正文进入证据。`pre-production-writer-*`保留最早使用备份连接的历史；最终`manual-client-real-server-production-writer.log`使用实际`Store::open_sync_writer`，没有创建或迁移数据库。

最终源码SHA见原始`verification.json`，接续保存时与当前源码比较见`current-source-comparison.json`。fmt、locked example check/build、strict example Clippy、Python编译通过。实际运行17条新CLI命令：一份真实Material准备两库、服务端descriptor登记、加密stage、真实HTTP ACK、丢ACK原字节重试、B认证下载和编辑、A拉取含echo、重复ACK、无回声pending、错误descriptor先拒绝、第二条密文被篡改后整页业务/游标回滚且原密文保留。最终两库各1条记录、cursor2、pending0。

初始Clippy err_expect及本地工具cluster路径/端口失败日志保留，修复后全部重跑。PG16.11和Go服务仅使用自有云目录、随机loopback端口和inline存储；夹具、材料、临时库均清理，PG停止。这不是Android/HTTPS、Docker/S3或真实Cloudflare R2验收。新client的Docker/S3 CI必须等包含新example的提交实际运行，不由相邻server74-ci的旧fixture成功替代。
