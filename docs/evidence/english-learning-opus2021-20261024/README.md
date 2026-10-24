# OPUS 2021 CPU 真实证据

2026-10-24；仅19条合成文字，不含用户输入、产品数据或云部署。结论和完整限制见[报告](../../ENGLISH_TRANSLATION_OPUS_2021_PROBE.md)。

- `download-manifest.json`：最终准备脚本在新目录验证的完整ZIP身份、固定十项成员、包内许可、decoder/NPZ配置和SPM。仅列经过校验的归档白名单。
- `conversion-result.json`：该准备结果的真实CT2转换，输出身份与下面四次推理使用的模型完全相同。
- `opus2021-beam1-online-capable.json`、`opus2021-beam1-offline.json`：beam 1的全部19句、规范化、译文、原sed后处理和资源观测。
- `opus2021-beam6-online-capable.json`、`opus2021-beam6-offline.json`：beam 6同样完整记录，没有筛掉坏例。
- `comparison.json`：四进程真实退出状态、整进程观测和两组配对一致性。
- `validation.json`：最终语法/规范化测试、固定资产复核、前轮相同输入及证据边界。

“offline”只指进程特定网络系统调用被拒绝，实际AF_INET/AF_INET6创建均EPERM；不是设备飞行模式或完整沙箱。主译文字段为SPM解码；原归档sed结果另保留首空格。峰值RSS为Linux Python进程观测，不含独立Perl子进程，也不是移动端内存承诺。

原权重包为Helsinki-NLP/OPUS-MT的CC-BY-4.0材料，来源URL、版本、完整SHA和成员SHA在manifest中。包内LICENSE与仓库既有`third_party/notices/opus-mt-zho-eng-20200717-CC-BY-4.0.txt`完全一致。转换/量化为本项目开发实验，运行库许可另记；权重及运行库未放进Git或APK。
