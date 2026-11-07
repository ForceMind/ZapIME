# 首轮 Android 数值门禁失败原件

运行 [42358906839](https://github.com/ForceMind/ZapIME/actions/runs/42358906839)，实现提交 `3a11177b7d0b2ef054183f5297a282dcb650a14f`，实际 PR merge checkout 为 `431a0cdb0fa84a25f19bde1daa3217a37a5afdee`。二者树均为 `6316761c0641aef8c7175800d79e703f8ca37f07`，已实际 fetch 比对。`provenance.json` 记录下载 ZIP 的 GitHub artifact ID、尺寸、SHA256 与每份原文件摘要；文件保持下载原字节。

API28、35、36 均在第三项 `real_model_msime_pool` 失败。实际加载了固定 APK 的 8M 模型与 ORT 1.28.2，三个 Android 平台输出相同：

| 固定候选 | Linux ORT 1.24.4 原参考 | Android ORT 1.28.2 实测 |
|---|---:|---:|
| 拟好 | -9.413619995117188 | -9.505149841308594 |
| 逆号 | -11.37354850769043 | -11.410218238830566 |
| 你好 | -3.759044647216797 | -3.7541027069091797 |

最大绝对误差约 0.09153，超过固定 0.001，所以**本轮失败**。首选词一致不能替代数值通过。三个探针均报告 native/core handle 归零；只完成前两项检查，不能称 14 项通过。由于原工作流顺序遇错即停，实际系统 IME 探针本轮没有执行。

修正验收方法：由固定上游 Python feed builders 生成输入张量，独立 test-only C API runner 在同一 Android 运行库执行两次图，再与生产 tokenizer/trie/cache 计算结果比较。参考资产不包含任何 Android 实测期望分数；不放宽 0.001，不减少原 14 项，不改生产模型或评分。旧主机参考保留为跨版本诊断值。新的方法与系统 IME 门禁各自执行一次，并保留各自退出状态，任一失败则整个 suite 失败。

这些原失败记录不能因后续通过而替换或改写。新源码 Android 成功必须来自其对应新运行。
