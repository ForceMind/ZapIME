# 固定Qwen配置CPU探针证据

全部输入来自前轮固定的同19条合成句，无用户聊天或剪贴板。原生推理确实运行，未接入输入法。

- `qwen-english-*`、`qwen-chinese-*`：两个完整38条结果按system提示分成四份JSON，每份19条。保留完整ChatML提示、官方模板、源句、原stdout/stderr、未裁剪正文、显示正文、所有命令参数、退出/EOG/结构结果、网络限制与单原生child的RSS/墙钟时间。分组仅重排存储，不改输出字符串；每份记完整原结果文件名及SHA。
- `comparison.json`：实际76个child、38个受限child、38组源句/提示/raw stdout/stderr/退出/EOG/资产配对全相同；结构通过不等于语义合格。只记录该温度0/禁思考/两提示配置，不外推模型其它设置。
- `asset-manifest.json`：官方模型LFS SHA、CPU包GitHub资产digest、固定版本/来源、许可、实际解压文件SHA。推理入口还与校验tar直接复核，不信任可变manifest。
- `model-LICENSE.txt`、`runtime-LICENSE.txt`：精确官方revision/commit的完整Apache-2.0与MIT主许可原文；不代表客户端所有系统/第三方依赖发行审计完成。
- `validation.json`：实际命令/退出、6项轻量回归、源码SHA及下载边界；新整条shell没有再次从空目录全跑。

“Tomorrow see.”、“Today is a bit stuck.”及“改天→tomorrow”等坏例全部保留。本配置未选为默认英语伴学输出。Qwen官方推荐的非思考采样不在本轮测试范围。

权重、二进制、官方C++源文件和缓存留在忽略的target。数据含暖文件缓存的单次观测，不是手机/iOS扩展/耗电/按键竞争或教学质量验收。详情与复现见[报告](../../ENGLISH_TRANSLATION_QWEN_PROBE.md)。
