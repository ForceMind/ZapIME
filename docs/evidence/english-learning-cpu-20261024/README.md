# 英语伴学独立CPU探针证据

全部输入为合成测试文字，无用户聊天、密码、Token或真实剪贴板。

- `probe-summary.json`为原探针综合记录；`inference-*.json`保留三句及扩充十句的原始输出，不覆盖坏例。
- `download-manifest.json`、`wheel-manifest.json`与`archive-notices.json`记录来源及实际SHA；不等于客户端完整供应链/资源分发审计。
- `original-probe/*.txt`是生成原证据的脚本/说明原文，内容SHA与summary对应；仅作历史取证，推荐入口为仓库scripts/translation_probe。原脚本均在忽略的target目录执行。
- `repository-cli-offline.json`与`repository-cli-validation.json`记录仓库路径适配后的实际重跑；模型及十句输出与原探针一致，未完整重跑整条下载shell。
- 权重、NPZ、模型、venv、wheels和缓存仍在target，不随这些证据提交。summary中的`all_artifacts_under_ignored_target`描述原运行时位置；之后仅复制了这里的文本证据。

结论和资源/质量限制见[探针报告](../../ENGLISH_TRANSLATION_PROBE.md)。当前不是手机、四端输入法或英语教学质量验收。
