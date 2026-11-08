# PR #96 同源码格式与源码包准备通过，发布未执行

工作流 [42466801643](https://github.com/ForceMind/ZapIME/actions/runs/42466801643) attempt 1 已最终 SUCCESS。head 为 `94b66f3e3ac9c0af92952427d0f3f1bfc27bb6a8`；完整 prepare job `128688077584` 日志记录实际 checkout/source commit `a0add63cc59cd6b3f7956bee4f04c7c805c72c1f`，与 Git API 核到的 tree `78d00ce936aafd38b7073e1dc688f502128a0e46` 对应。该身份与本轮[候选下载失败](../pr96-candidate-download-42466801775/README.md)相同，两个结果分别保留。

实际已通过：30 项真实 Git 发布准备/安全重试回归、固定上游身份检查、Rust1.97.1 workspace 134 项测试、`cargo fmt --all --check`、严格 Clippy，以及从该精确 committed source 创建源码 Alpha 容器。旧 head 的格式失败不能覆盖这次成功，当前成功也不能覆盖旧原失败。

GitHub 返回源码 artifact `13612027161`，名称 `ZapIME-source-alpha-a0add63cc59cd6b3f7956bee4f04c7c805c72c1f`，ZIP 元数据尺寸 11,044,726 字节、digest `sha256:e72d47825d6292614601c5836e7f167905ccfc507ca2e8323e32dd613da434c5`。本目录仅独立回读 job/API，**没有下载核验此 ZIP 内容**；不能将该 digest 描述为本地下载摘要，也不据此声称归档解压后重新执行过 Cargo。

`Publish source-only Alpha prerelease` job `128688361936` 因条件 skipped；没有实际创建新公开版本，没有覆盖/移动现有 `v0.1.0-alpha.1` 标签或附件。源码包准备成功不等于公开发布成功，也不能代填本轮候选模型、系统 IME、配置实验或手机验收。

完整 connector decoded UTF-8 job log 按原文本保存为 mtime=0 gzip；原文本和压缩文件的 bytes/SHA256 记录在 `decoded-log-provenance.json`。它不是原始 Actions 日志 ZIP。`readback.json` 严格区分本轮源码检查通过、artifact 仅元数据回读和发布 skipped；`manifest.json` 校验本目录除自身外的全部文件。
