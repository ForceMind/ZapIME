# 首轮本机同步材料 JNI / Keystore 专项：三 API 实际通过

产品源码 `9e095fccd78dbb2240e147d60d9543143f8c7d43`，[focused run42347009455](https://github.com/ForceMind/ZapIME/actions/runs/42347009455)。仅下列 local-sync-preparation 作业的原始结果，不把整个工作流或后续 UI 源码自动称为通过。

| API | Job | Artifact | ZIP bytes | 实算 ZIP SHA256 |
| --- | --- | --- | ---: | --- |
| 28 | 128328754545 | 13564276740 | 1376 | `5bf8b8d3c829f97620cc8aa42f5da3a38b525eafce7ec620f1b572ac40eac694` |
| 35 | 128328754503 | 13564315817 | 1465 | `8ddf22981c85ce43d1297c138e22ad26c11bef2a3dab61589a1ced8281402e6a` |
| 36 | 128328754500 | 13564032740 | 1448 | `c8d96f6551106aa58ed82790f7deba6b4346f638e44b23215671aa9ce5aa49e3` |

三个 metadata ZIP 均已实际下载，逐一核对 GitHub artifact digest、大小、run 与源码，再用当前仓库的 `android_sync_preparation_report.verify_report(report, api)` 严格重验。每端全部 11 项通过，`cleanup_completed=true`。`offline-uid.json` 另核对精确产品包名/UID、IPv4 和 IPv6 REJECT，以及 `removed=true`。

覆盖真实 stateless JNI、Android Keystore、create-only 密钥材料、资料库账号/key_id/根材料指纹绑定、同材料中断重试、认证密钥恢复、已有材料及业务行保留、成员新建限制与旧材料复用、页面隐私及旧 generation 拒绝。SQLite 模板由生产 NativeCore 初始化、关闭并 checkpoint 后复制；不以手造不完整表替代生产库。

**不覆盖** HTTP、云端密钥登记、个人数据同步、自然语音、手机、正向系统文件导出/导入。此源码早于新增 SAF UI 的 `5b2d9488`；这些成功不能证明后续 UI 修改。后续真实结果另建目录，不覆盖本记录。

本目录仅保存原 metadata/report/log 文本、GitHub元数据和逐文件摘要。没有模型、音频、APK、真实个人资料、密钥/恢复码或临时带签名下载URL。GitHub job log API返回的是已解码文字，保存其原文字UTF-8字节，记录相应SHA。
